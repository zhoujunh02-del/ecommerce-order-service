package com.ecommerce.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.ErrorCode;
import com.ecommerce.common.id.UuidV7;
import com.ecommerce.inventory.api.dto.ReserveLine;
import com.ecommerce.inventory.app.StockService;
import com.ecommerce.inventory.domain.Inventory;
import com.ecommerce.inventory.infra.mapper.InventoryMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the conditional UPDATE prevents overselling under heavy contention.
 * Runs against a REAL PostgreSQL (Testcontainers), with Flyway applying the same
 * migrations as production, so sku 2001 starts with available = 100.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class StockServiceConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    StockService stockService;

    @Autowired
    InventoryMapper inventoryMapper;

    @Test
    void reserveNeverOversells() throws Exception {
        long skuId = 2001;      // seeded with available = 100
        int stock = 100;
        int attempts = 1000;    // 10x more buyers than units
        int threads = 100;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();              // release all threads at once → max contention
                try {
                    stockService.reserve(UuidV7.generate(), List.of(new ReserveLine(skuId, 1)));
                    succeeded.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.code() == ErrorCode.INSUFFICIENT_STOCK) {
                        insufficient.incrementAndGet();
                    } else {
                        unexpected.add(e);
                    }
                } catch (Throwable t) {
                    unexpected.add(t);
                }
                return null;
            }));
        }

        startGate.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(unexpected).as("no unexpected errors").isEmpty();
        assertThat(succeeded.get()).as("exactly the stock count succeeds").isEqualTo(stock);
        assertThat(insufficient.get()).as("everyone else is rejected").isEqualTo(attempts - stock);

        Inventory inv = inventoryMapper.findBySkuId(skuId);
        assertThat(inv.available()).as("no oversell: available never goes negative").isZero();
        assertThat(inv.reserved()).isEqualTo(stock);
        assertThat(inv.sold()).isZero();
    }
}
