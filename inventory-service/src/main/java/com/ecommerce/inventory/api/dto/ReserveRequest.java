package com.ecommerce.inventory.api.dto;

import java.util.List;
import java.util.UUID;

/** Body of POST /internal/inventory/reserve. orderId is the idempotency key. */
public record ReserveRequest(UUID orderId, List<ReserveLine> lines) {
}
