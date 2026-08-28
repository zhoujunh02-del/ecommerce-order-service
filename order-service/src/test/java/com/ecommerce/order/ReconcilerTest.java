package com.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.CreateOrderRequest.OrderItemRequest;
import com.ecommerce.order.app.OrderAppService;
import com.ecommerce.order.infra.client.InventoryClient;
import com.ecommerce.order.infra.client.InventoryUnavailableException;
import com.ecommerce.order.job.CreatingOrderReconciler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Saga converges: an order left CREATING by an uncertain reserve is resolved by
 * asking inventory what actually happened.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.kafka.listener.auto-startup=false",
        "outbox.relay-interval-ms=3600000",
        "order.timeout.scan-interval-ms=3600000",
        "order.reconcile.interval-ms=3600000",     // don't auto-fire; we call it manually
        "order.reconcile.stuck-after-seconds=0"     // treat brand-new CREATING orders as stuck
})
class ReconcilerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    OrderAppService orderAppService;

    @Autowired
    CreatingOrderReconciler reconciler;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    InventoryClient inventoryClient;

    /** Places an order whose reserve fails technically, leaving it stuck in CREATING. */
    private UUID placeStuckOrder(long userId, String idemKey) {
        doThrow(new InventoryUnavailableException("timeout"))
                .when(inventoryClient).reserve(any(UUID.class), anyList());
        assertThatThrownBy(() -> orderAppService.placeOrder(userId, idemKey,
                new CreateOrderRequest(List.of(new OrderItemRequest(2001, 1)))))
                .isInstanceOf(BusinessException.class);
        return jdbc.queryForObject(
                "SELECT id FROM orders WHERE user_id = ? AND status = 'CREATING'", UUID.class, userId);
    }

    @Test
    void stuckOrder_reservedInInventory_isConfirmedAndIdempotencyCompleted() {
        String idemKey = UUID.randomUUID().toString();
        UUID orderId = placeStuckOrder(9400, idemKey);

        when(inventoryClient.queryReservation(orderId)).thenReturn("RESERVED");
        reconciler.reconcile();

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT status FROM idempotency WHERE idem_key = ?", String.class, idemKey))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'OrderCreated'",
                Integer.class, orderId)).isEqualTo(1);
    }

    @Test
    void stuckOrder_notReservedInInventory_isFailed() {
        String idemKey = UUID.randomUUID().toString();
        UUID orderId = placeStuckOrder(9401, idemKey);

        when(inventoryClient.queryReservation(orderId)).thenReturn("NOT_FOUND");
        reconciler.reconcile();

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("FAILED");
    }
}
