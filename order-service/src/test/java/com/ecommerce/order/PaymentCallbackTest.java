package com.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.ErrorCode;
import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.CreateOrderRequest.OrderItemRequest;
import com.ecommerce.order.api.dto.OrderResponse;
import com.ecommerce.order.app.OrderAppService;
import com.ecommerce.order.app.PaymentService;
import com.ecommerce.order.infra.client.InventoryClient;
import com.ecommerce.order.infra.payment.HmacSigner;
import java.math.BigDecimal;
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
        "order.timeout.scan-interval-ms=3600000"
})
class PaymentCallbackTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    OrderAppService orderAppService;

    @Autowired
    PaymentService paymentService;

    @Autowired
    HmacSigner signer;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    InventoryClient inventoryClient;

    private OrderResponse placeOrder(long userId, long skuId, int qty) {
        doNothing().when(inventoryClient).reserve(any(UUID.class), anyList());
        return orderAppService.placeOrder(userId, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new OrderItemRequest(skuId, qty))));
    }

    private String sign(UUID orderId, String payNo, BigDecimal amount) {
        return signer.sign(orderId + "|" + payNo + "|" + amount.toPlainString());
    }

    @Test
    void invalidSignature_isRejected() {
        OrderResponse order = placeOrder(9300, 2001, 1);
        assertThatThrownBy(() -> paymentService.handleCallback(
                order.orderId(), "PAY1", order.totalAmount(), "deadbeef"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.PAYMENT_SIGNATURE_INVALID);
    }

    @Test
    void amountMismatch_recordsAnomalyAndDoesNotPay() {
        OrderResponse order = placeOrder(9301, 2001, 1);
        BigDecimal wrong = new BigDecimal("0.01");

        paymentService.handleCallback(order.orderId(), "PAY2", wrong, sign(order.orderId(), "PAY2", wrong));

        String status = jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.orderId());
        assertThat(status).isEqualTo("PENDING_PAYMENT");
        Integer anomalies = jdbc.queryForObject(
                "SELECT count(*) FROM payment_anomaly WHERE order_id = ? AND reason = 'AMOUNT_MISMATCH'",
                Integer.class, order.orderId());
        assertThat(anomalies).isEqualTo(1);
    }

    @Test
    void validCallback_paysOnceEvenIfRedelivered() {
        OrderResponse order = placeOrder(9302, 2001, 1);
        String payNo = "PAY3";
        String sig = sign(order.orderId(), payNo, order.totalAmount());

        paymentService.handleCallback(order.orderId(), payNo, order.totalAmount(), sig);
        paymentService.handleCallback(order.orderId(), payNo, order.totalAmount(), sig);  // redelivery

        String status = jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.orderId());
        assertThat(status).isEqualTo("PAID");
        Integer paidEvents = jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'OrderPaid'",
                Integer.class, order.orderId());
        assertThat(paidEvents).isEqualTo(1);   // exactly one OrderPaid despite two callbacks
    }

    @Test
    void paidButCancelled_queuesRefund() {
        OrderResponse order = placeOrder(9303, 2001, 1);
        // Simulate the timeout cancel winning the race.
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", order.orderId());

        String payNo = "PAY4";
        paymentService.handleCallback(order.orderId(), payNo, order.totalAmount(),
                sign(order.orderId(), payNo, order.totalAmount()));

        Integer refunds = jdbc.queryForObject(
                "SELECT count(*) FROM pending_refund WHERE order_id = ?", Integer.class, order.orderId());
        assertThat(refunds).isEqualTo(1);
    }
}
