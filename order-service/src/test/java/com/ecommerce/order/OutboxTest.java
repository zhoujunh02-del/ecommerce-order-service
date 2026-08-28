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
 * The transactional outbox: a state change and its event are written in one
 * transaction, so the event is guaranteed to exist whenever the state changed.
 * (Relay disabled here so the rows stay for inspection.)
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.kafka.listener.auto-startup=false",
        "outbox.relay-interval-ms=3600000"
})
class OutboxTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    OrderAppService orderAppService;

    @Autowired
    PaymentService paymentService;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    InventoryClient inventoryClient;

    @Test
    void placeAndPay_writeCreatedAndPaidEventsToOutbox() {
        doNothing().when(inventoryClient).reserve(any(UUID.class), anyList());

        OrderResponse order = orderAppService.placeOrder(9100, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new OrderItemRequest(2001, 1))));
        paymentService.mockPay(order.orderId());

        Integer created = jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'OrderCreated'",
                Integer.class, order.orderId());
        Integer paid = jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'OrderPaid'",
                Integer.class, order.orderId());

        assertThat(created).isEqualTo(1);
        assertThat(paid).isEqualTo(1);
    }
}
