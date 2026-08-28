package com.ecommerce.order.job;

import com.ecommerce.common.event.OrderEvent;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderStatus;
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
 * Cancels orders that were never paid before their deadline. Runs as a periodic
 * database scan (not a message-queue delay): timeout close is low-frequency,
 * latency-tolerant, must-not-be-lost, and the data already lives in the orders
 * table — so a scan is the right tool.
 *
 * <p>Each tick claims a batch with FOR UPDATE SKIP LOCKED, cancels each order, and
 * writes an OrderCancelled event to the outbox — all in ONE transaction, so the
 * cancellation and the stock-release event cannot diverge. The transition is
 * conditional (WHERE status = PENDING_PAYMENT), so if a payment callback wins the
 * race the scan simply affects zero rows for that order.
 */
@Component
public class TimeoutScanner {

    private static final Logger log = LoggerFactory.getLogger(TimeoutScanner.class);

    private final TransactionTemplate tx;
    private final OrderMapper orderMapper;
    private final OutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public TimeoutScanner(TransactionTemplate tx,
                          OrderMapper orderMapper,
                          OutboxMapper outboxMapper,
                          ObjectMapper objectMapper,
                          @Value("${order.timeout.batch-size}") int batchSize) {
        this.tx = tx;
        this.orderMapper = orderMapper;
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${order.timeout.scan-interval-ms}")
    public void scan() {
        tx.executeWithoutResult(s -> {
            List<Order> expired = orderMapper.lockExpiredBatch(batchSize);
            for (Order order : expired) {
                int affected = orderMapper.transition(
                        order.id(), OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, "PAYMENT_TIMEOUT");
                if (affected == 0) {
                    continue;   // a payment callback moved it first
                }
                outboxMapper.insert(order.id(), OrderEvent.ORDER_CANCELLED,
                        toJson(OrderEvent.of(OrderEvent.ORDER_CANCELLED, order.id(), order.userId())));
                log.info("Order {} cancelled by timeout scan", order.id());
            }
        });
    }

    private String toJson(OrderEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize event", e);
        }
    }
}
