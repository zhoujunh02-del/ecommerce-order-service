package com.ecommerce.inventory.api.dto;

/** Result of reserve/commit/release: RESERVED / COMMITTED / RELEASED. */
public record StockActionResponse(String status) {
}
