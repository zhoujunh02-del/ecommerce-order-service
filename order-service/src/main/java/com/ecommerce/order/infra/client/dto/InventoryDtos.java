package com.ecommerce.order.infra.client.dto;

import java.util.List;
import java.util.UUID;

/**
 * Wire DTOs for calling inventory-service. These are intentionally SEPARATE from
 * inventory-service's own DTO classes: services share a wire format, not code.
 * Coupling them to the same class would tie the two services together at compile time.
 */
public final class InventoryDtos {

    private InventoryDtos() {
    }

    public record ReserveRequest(UUID orderId, List<ReserveLine> lines) {
    }

    public record ReserveLine(long skuId, int quantity) {
    }
}
