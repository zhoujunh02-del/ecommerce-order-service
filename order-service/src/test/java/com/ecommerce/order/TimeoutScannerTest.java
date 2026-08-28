package com.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;

import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.CreateOrderRequest.OrderItemRequest;
import com.ecommerce.order.api.dto.OrderResponse;
import com.ecommerce.order.app.OrderAppService;
import com.ecommerce.order.app.PaymentService;
import com.ecommerce.order.infra.client.InventoryClient;
import com.ecommerce.order.job.TimeoutScanner;
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

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.kafka.listener.auto-startup=false",
        "outbox.relay-interval-ms=3600000",
        "order.timeout.scan-interval-ms=3600000"   // don't let the scheduled scan fire; we call it manually
})
class TimeoutScannerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    OrderAppService orderAppService;

    @Autowired
    TimeoutScanner timeoutScanner;

    @Autowired
    PaymentService paymentService;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    InventoryClient inventoryClient;

    @Test
    void expiredUnpaidOrder_isCancelledAndEmitsReleaseEvent() {
        doNothing().when(inventoryClient).reserve(any(UUID.class), anyList());

        OrderResponse order = orderAppService.placeOrder(9200, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new OrderItemRequest(2001, 1))));

        // Force the order past its payment deadline.
        jdbc.update("UPDATE orders SET expire_at = now() - interval '1 minute' WHERE id = ?",
                order.orderId());

        timeoutScanner.scan();

        String status = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, order.orderId());
        assertThat(status).isEqualTo("CANCELLED");

        Integer cancelledEvents = jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'OrderCancelled'",
                Integer.class, order.orderId());
        assertThat(cancelledEvents).isEqualTo(1);
    }

    @Test
    void paidOrder_isNotTouchedByScan() {
        doNothing().when(inventoryClient).reserve(any(UUID.class), anyList());

        OrderResponse order = orderAppService.placeOrder(9201, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new OrderItemRequest(2002, 1))));
        paymentService.mockPay(order.orderId());

        // Even if its deadline passes, a PAID order must be ignored by the scan.
        jdbc.update("UPDATE orders SET expire_at = now() - interval '1 minute' WHERE id = ?",
                order.orderId());

        timeoutScanner.scan();

        String status = jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, order.orderId());
        assertThat(status).isEqualTo("PAID");
    }
}
