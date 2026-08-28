package com.ecommerce.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.common.id.UuidV7;
import com.ecommerce.inventory.api.dto.ReserveLine;
import com.ecommerce.inventory.app.StockService;
import com.ecommerce.inventory.domain.Inventory;
import com.ecommerce.inventory.infra.mapper.InventoryMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * commit/release must be idempotent because Kafka delivers at-least-once: the same
 * OrderPaid/OrderCancelled can be redelivered, and applying it twice must not move
 * stock twice.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class CommitReleaseIdempotencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    StockService stockService;

    @Autowired
    InventoryMapper inventoryMapper;

    @Test
    void commitAppliedTwice_movesStockOnce() {
        UUID orderId = UuidV7.generate();
        stockService.reserve(orderId, List.of(new ReserveLine(2001, 2)));

        stockService.commit(orderId);
        stockService.commit(orderId);   // redelivery

        Inventory inv = inventoryMapper.findBySkuId(2001);
        assertThat(inv.available()).isEqualTo(98);
        assertThat(inv.reserved()).isZero();
        assertThat(inv.sold()).isEqualTo(2);
    }

    @Test
    void releaseAppliedTwice_movesStockOnce() {
        UUID orderId = UuidV7.generate();
        stockService.reserve(orderId, List.of(new ReserveLine(2002, 3)));

        stockService.release(orderId);
        stockService.release(orderId);   // redelivery

        Inventory inv = inventoryMapper.findBySkuId(2002);
        assertThat(inv.available()).isEqualTo(500);
        assertThat(inv.reserved()).isZero();
        assertThat(inv.sold()).isZero();
    }
}
