package com.ecommerce.inventory.api.dto;

import java.util.UUID;

/** Body of commit/release: just the order id. Quantities come from the ledger. */
public record OrderRefRequest(UUID orderId) {
}
