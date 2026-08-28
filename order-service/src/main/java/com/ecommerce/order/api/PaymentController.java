package com.ecommerce.order.api;

import com.ecommerce.order.api.dto.PaymentCallbackRequest;
import com.ecommerce.order.app.PaymentService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** Payment gateway callback. Always answers SUCCESS unless the signature is invalid. */
    @PostMapping("/api/v1/payments/callback")
    public Map<String, String> callback(@RequestBody PaymentCallbackRequest req,
                                        @RequestHeader("X-Signature") String signature) {
        paymentService.handleCallback(req.orderId(), req.payNo(), req.amount(), signature);
        return Map.of("code", "SUCCESS");
    }

    /** Test/load helper: acts as the gateway, sending a correctly-signed callback. */
    @PostMapping("/api/v1/mock-payment/{orderId}/pay")
    public Map<String, String> mockPay(@PathVariable UUID orderId) {
        paymentService.mockPay(orderId);
        return Map.of("orderId", orderId.toString(), "status", "PAID");
    }
}
