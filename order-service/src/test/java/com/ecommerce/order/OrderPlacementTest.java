package com.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.ErrorCode;
import com.ecommerce.order.api.dto.CreateOrderRequest;
import com.ecommerce.order.api.dto.CreateOrderRequest.OrderItemRequest;
import com.ecommerce.order.api.dto.OrderResponse;
import com.ecommerce.order.app.OrderAppService;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.infra.client.InventoryClient;
import com.ecommerce.order.infra.mapper.OrderMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Locks in the order state machine around the reserve call, with inventory-service
 * mocked out so the test is about order-service's own logic and transaction flow.
 */
@SpringBootTest
@Testcontainers
class OrderPlacementTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    OrderAppService orderAppService;

    @Autowired
    OrderMapper orderMapper;

    @MockitoBean
    InventoryClient inventoryClient;

    private static final long USER = 7001;

    @Test
    void reserveSuccess_movesOrderToPendingPayment() {
        doNothing().when(inventoryClient).reserve(any(UUID.class), anyList());

        CreateOrderRequest req = new CreateOrderRequest(List.of(new OrderItemRequest(2001, 2)));
        OrderResponse resp = orderAppService.placeOrder(USER, req);

        assertThat(resp.status()).isEqualTo("PENDING_PAYMENT");
        Order persisted = orderMapper.findById(resp.orderId());
        assertThat(persisted.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(persisted.totalAmount()).isEqualByComparingTo("598.00");
    }

    @Test
    void reserveInsufficient_movesOrderToFailed() {
        doThrow(new BusinessException(ErrorCode.INSUFFICIENT_STOCK, "no stock"))
                .when(inventoryClient).reserve(any(UUID.class), anyList());

        CreateOrderRequest req = new CreateOrderRequest(List.of(new OrderItemRequest(2004, 2)));

        assertThatThrownBy(() -> orderAppService.placeOrder(8002, req))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.INSUFFICIENT_STOCK);

        // The order must be persisted as FAILED, not left dangling in CREATING.
        Order latest = orderMapper.findPage(8002, null, null, 1).get(0);
        assertThat(latest.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(latest.failReason()).isEqualTo("INSUFFICIENT_STOCK");
    }
}
