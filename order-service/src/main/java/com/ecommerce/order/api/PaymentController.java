package com.ecommerce.order.api;

import com.ecommerce.order.app.OrderAppService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stand-in for a payment gateway callback, so tests and load scripts can drive an
 * order to PAID without a real payment integration. Phase 3 adds the realistic
 * signed/idempotent callback at /api/v1/payments/callback.
 */
@RestController
public class PaymentController {

    private final OrderAppService orderAppService;

    public PaymentController(OrderAppService orderAppService) {
        this.orderAppService = orderAppService;
    }

    @PostMapping("/api/v1/mock-payment/{orderId}/pay")
    public Map<String, String> pay(@PathVariable UUID orderId) {
        orderAppService.pay(orderId);
        return Map.of("orderId", orderId.toString(), "status", "PAID");
    }
}
