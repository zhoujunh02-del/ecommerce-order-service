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
import com.ecommerce.order.infra.client.InventoryClient;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class IdempotencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    OrderAppService orderAppService;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    InventoryClient inventoryClient;

    @Test
    void sameKeyConcurrently_createsExactlyOneOrder() throws Exception {
        doNothing().when(inventoryClient).reserve(any(UUID.class), anyList());

        long userId = 5001;
        String key = UUID.randomUUID().toString();
        CreateOrderRequest req = new CreateOrderRequest(List.of(new OrderItemRequest(2001, 1)));

        int attempts = 30;
        ExecutorService pool = Executors.newFixedThreadPool(30);
        CountDownLatch gate = new CountDownLatch(1);
        ConcurrentLinkedQueue<UUID> successIds = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> errors = new ConcurrentLinkedQueue<>();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    OrderResponse r = orderAppService.placeOrder(userId, key, req);
                    successIds.add(r.orderId());
                } catch (BusinessException e) {
                    errors.add(e.code());
                }
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        // Exactly one order row for this user, no matter how many concurrent submits.
        Integer orderCount = jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE user_id = ?", Integer.class, userId);
        assertThat(orderCount).isEqualTo(1);

        // Every successful caller saw the same order id; losers got REQUEST_IN_PROGRESS.
        assertThat(successIds).isNotEmpty();
        assertThat(successIds.stream().distinct().count()).isEqualTo(1L);
        assertThat(errors).allMatch(c -> c == ErrorCode.REQUEST_IN_PROGRESS);
    }

    @Test
    void completedKeyReplays_sameOrderId() {
        doNothing().when(inventoryClient).reserve(any(UUID.class), anyList());

        String key = UUID.randomUUID().toString();
        CreateOrderRequest req = new CreateOrderRequest(List.of(new OrderItemRequest(2002, 3)));

        OrderResponse first = orderAppService.placeOrder(6001, key, req);
        OrderResponse replay = orderAppService.placeOrder(6001, key, req);

        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(replay.status()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void sameKeyDifferentBody_isRejected() {
        doNothing().when(inventoryClient).reserve(any(UUID.class), anyList());

        String key = UUID.randomUUID().toString();
        orderAppService.placeOrder(7001, key, new CreateOrderRequest(List.of(new OrderItemRequest(2001, 1))));

        assertThatThrownBy(() -> orderAppService.placeOrder(
                7001, key, new CreateOrderRequest(List.of(new OrderItemRequest(2001, 2)))))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }
}
