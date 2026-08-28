package com.ecommerce.order.api;

import com.ecommerce.order.domain.Order;
import com.ecommerce.order.infra.mapper.OrderMapper;
import com.ecommerce.order.infra.mapper.OutboxMapper;
import com.ecommerce.order.infra.mapper.PaymentMapper;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A window for human intervention. Distributed systems are never 100% self-healing:
 * events that exhausted retries (DEAD), orders stuck in CREATING, and payments that
 * need a refund all need a person eventually. This endpoint surfaces them.
 */
@RestController
public class AdminController {

    private final OrderMapper orderMapper;
    private final OutboxMapper outboxMapper;
    private final PaymentMapper paymentMapper;

    public AdminController(OrderMapper orderMapper, OutboxMapper outboxMapper, PaymentMapper paymentMapper) {
        this.orderMapper = orderMapper;
        this.outboxMapper = outboxMapper;
        this.paymentMapper = paymentMapper;
    }

    @GetMapping("/internal/admin/orders/stuck")
    public Map<String, Object> stuck() {
        List<Order> stuck = orderMapper.findStuckCreating(60, 50);
        List<Map<String, Object>> stuckView = stuck.stream()
                .map(o -> Map.<String, Object>of("orderId", o.id(), "createdAt", o.createdAt()))
                .toList();
        return Map.of(
                "stuckCreatingCount", stuck.size(),
                "stuckCreating", stuckView,
                "outboxPending", outboxMapper.countByStatus("PENDING"),
                "outboxDead", outboxMapper.countByStatus("DEAD"),
                "pendingRefunds", paymentMapper.countPendingRefunds());
    }
}
