package com.ecommerce.inventory.api.dto;

/** Public view of a SKU's stock. */
public record InventoryResponse(long skuId, int available, int reserved, int sold) {
}
