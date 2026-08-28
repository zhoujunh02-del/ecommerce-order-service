package com.ecommerce.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.ErrorCode;
import com.ecommerce.common.id.UuidV7;
import com.ecommerce.inventory.api.dto.ReserveLine;
import com.ecommerce.inventory.app.StockService;
import com.ecommerce.inventory.domain.Inventory;
import com.ecommerce.inventory.infra.mapper.InventoryMapper;
import com.ecommerce.inventory.infra.redis.RedisStockService;
import com.ecommerce.inventory.job.StockConsistencyChecker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The trust boundary: Redis is a performance layer, the database is the source of
 * truth. Even if Redis is wildly wrong (inflated), the conditional update in the DB
 * still prevents overselling.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.kafka.listener.auto-startup=false",
        "inventory.redis.enabled=true"
})
class RedisTwoTierTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    StockService stockService;

    @Autowired
    RedisStockService redisStock;

    @Autowired
    InventoryMapper inventoryMapper;

    @Autowired
    StockConsistencyChecker checker;

    @Test
    void inflatedRedis_stillDoesNotOversell() throws Exception {
        long skuId = 2004;   // seeded with 50
        int stock = 50;
        int attempts = 300;

        checker.reconcile();               // warm Redis to the DB value
        redisStock.sync(skuId, 1_000_000); // now corrupt it: pretend there's plenty

        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    stockService.reserve(UuidV7.generate(), List.of(new ReserveLine(skuId, 1)));
                    succeeded.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.code() != ErrorCode.INSUFFICIENT_STOCK) {
                        throw e;
                    }
                }
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        // The database capped it at the real stock, regardless of Redis being inflated.
        assertThat(succeeded.get()).isEqualTo(stock);
        Inventory inv = inventoryMapper.findBySkuId(skuId);
        assertThat(inv.available()).isZero();
        assertThat(inv.reserved()).isEqualTo(stock);
        assertThat(inv.sold()).isZero();
    }
}
