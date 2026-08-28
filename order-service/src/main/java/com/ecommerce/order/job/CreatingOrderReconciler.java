package com.ecommerce.order.job;

import com.ecommerce.common.error.ErrorCode;
import com.ecommerce.common.event.OrderEvent;
import com.ecommerce.order.api.dto.OrderResponse;
import com.ecommerce.order.api.dto.OrderResponse.OrderItemResponse;
import com.ecommerce.order.app.IdemOutcome;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderStatus;
import com.ecommerce.order.infra.client.InventoryClient;
import com.ecommerce.order.infra.mapper.IdempotencyMapper;
import com.ecommerce.order.infra.mapper.OrderItemMapper;
import com.ecommerce.order.infra.mapper.OrderItemRow;
import com.ecommerce.order.infra.mapper.OrderMapper;
import com.ecommerce.order.infra.mapper.OutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves orders stuck in CREATING — the case where the synchronous reserve did
 * not return a definite answer (timeout, circuit open). For each such order it asks
 * inventory-service what actually happened (the status-query endpoint) and drives
 * the order to a terminal-ish state accordingly. This is what lets the Saga
 * converge after an uncertain reserve; without it, a timed-out order would hang
 * forever. It also completes the pending idempotency record so client retries stop
 * getting REQUEST_IN_PROGRESS.
 */
@Component
public class CreatingOrderReconciler {

    private static final Logger log = LoggerFactory.getLogger(CreatingOrderReconciler.class);

    private final TransactionTemplate tx;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OutboxMapper outboxMapper;
    private final IdempotencyMapper idempotencyMapper;
    private final InventoryClient inventoryClient;
    private final ObjectMapper objectMapper;
    private final long stuckAfterSeconds;
    private final int batchSize;

    public CreatingOrderReconciler(TransactionTemplate tx,
                                   OrderMapper orderMapper,
                                   OrderItemMapper orderItemMapper,
                                   OutboxMapper outboxMapper,
                                   IdempotencyMapper idempotencyMapper,
                                   InventoryClient inventoryClient,
                                   ObjectMapper objectMapper,
                                   @Value("${order.reconcile.stuck-after-seconds}") long stuckAfterSeconds,
                                   @Value("${order.reconcile.batch-size}") int batchSize) {
        this.tx = tx;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.outboxMapper = outboxMapper;
        this.idempotencyMapper = idempotencyMapper;
        this.inventoryClient = inventoryClient;
        this.objectMapper = objectMapper;
        this.stuckAfterSeconds = stuckAfterSeconds;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${order.reconcile.interval-ms}")
    public void reconcile() {
        for (Order order : orderMapper.findStuckCreating(stuckAfterSeconds, batchSize)) {
            String status;
            try {
                status = inventoryClient.queryReservation(order.id());
            } catch (RuntimeException e) {
                log.warn("Reconcile: inventory unreachable for order {}, will retry", order.id());
                continue;   // try again next tick
            }
            switch (status) {
                case "RESERVED", "COMMITTED" -> resolveConfirmed(order);
                case "NOT_FOUND", "RELEASED" -> resolveFailed(order, "RESERVE_" + status);
                default -> log.warn("Reconcile: unexpected status {} for order {}", status, order.id());
            }
        }
    }

    private void resolveConfirmed(Order order) {
        tx.executeWithoutResult(s -> {
            int affected = orderMapper.transition(
                    order.id(), OrderStatus.CREATING, OrderStatus.PENDING_PAYMENT, null);
            if (affected == 0) {
                return;
            }
            outboxMapper.insert(order.id(), OrderEvent.ORDER_CREATED,
                    toJson(OrderEvent.of(OrderEvent.ORDER_CREATED, order.id(), order.userId())));
            completeIdempotency(order, IdemOutcome.ok(buildResponse(order)));
            log.info("Reconcile: order {} confirmed RESERVED -> PENDING_PAYMENT", order.id());
        });
    }

    private void resolveFailed(Order order, String reason) {
        tx.executeWithoutResult(s -> {
            int affected = orderMapper.transition(
                    order.id(), OrderStatus.CREATING, OrderStatus.FAILED, reason);
            if (affected == 0) {
                return;
            }
            completeIdempotency(order, IdemOutcome.error(ErrorCode.INTERNAL_ERROR.name(), reason));
            log.info("Reconcile: order {} resolved to FAILED ({})", order.id(), reason);
        });
    }

    private void completeIdempotency(Order order, IdemOutcome outcome) {
        if (order.idemKey() != null) {
            idempotencyMapper.complete(order.idemKey(), toJson(outcome));
        }
    }

    private OrderResponse buildResponse(Order order) {
        List<OrderItemResponse> items = orderItemMapper.findByOrderId(order.id()).stream()
                .map(this::toItemResponse).toList();
        return new OrderResponse(order.id(), order.orderNo(), OrderStatus.PENDING_PAYMENT.name(),
                order.totalAmount(), order.currency(), order.expireAt(), items);
    }

    private OrderItemResponse toItemResponse(OrderItemRow row) {
        return new OrderItemResponse(row.skuId(), row.skuName(), row.unitPrice(), row.quantity());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize", e);
        }
    }
}
