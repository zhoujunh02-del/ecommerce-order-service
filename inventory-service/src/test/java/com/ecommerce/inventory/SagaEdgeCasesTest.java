package com.ecommerce.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.ErrorCode;
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

/** The TCC edge cases: idempotence, empty rollback, and hanging. */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.kafka.listener.auto-startup=false",
        "inventory.redis.enabled=false"
})
class SagaEdgeCasesTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    StockService stockService;

    @Autowired
    InventoryMapper inventoryMapper;

    @Test
    void reserveTwice_deductsOnce_andReportsReserved() {
        UUID orderId = UuidV7.generate();
        stockService.reserve(orderId, List.of(new ReserveLine(2003, 2)));
        stockService.reserve(orderId, List.of(new ReserveLine(2003, 2)));   // retry

        Inventory inv = inventoryMapper.findBySkuId(2003);
        assertThat(inv.available()).isEqualTo(198);
        assertThat(inv.reserved()).isEqualTo(2);
        assertThat(stockService.queryReservation(orderId)).isEqualTo("RESERVED");
    }

    @Test
    void releaseWithoutReserve_isEmptyRollback_thenLateReserveHangs() {
        UUID orderId = UuidV7.generate();

        // Empty rollback: a release arrives with no reservation. No stock changes,
        // but a RELEASE marker is recorded.
        stockService.release(orderId);
        assertThat(stockService.queryReservation(orderId)).isEqualTo("RELEASED");
        Inventory before = inventoryMapper.findBySkuId(2001);
        assertThat(before.available()).isEqualTo(100);
        assertThat(before.reserved()).isZero();

        // Hanging: the late reserve for that order must be rejected, not applied.
        assertThatThrownBy(() -> stockService.reserve(orderId, List.of(new ReserveLine(2001, 1))))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.ORDER_STATE_CONFLICT);

        Inventory after = inventoryMapper.findBySkuId(2001);
        assertThat(after.available()).isEqualTo(100);
        assertThat(after.reserved()).isZero();
    }

    @Test
    void queryReservation_unknownOrder_isNotFound() {
        assertThat(stockService.queryReservation(UuidV7.generate())).isEqualTo("NOT_FOUND");
    }
}
