package com.ecommerce.order.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Body of the payment gateway callback. The signature travels in the X-Signature header. */
public record PaymentCallbackRequest(UUID orderId, String payNo, BigDecimal amount) {
}
