package com.ecommerce.inventory.api.dto;

/** One line of a reserve request: how many units of a SKU to hold. */
public record ReserveLine(long skuId, int quantity) {
}
