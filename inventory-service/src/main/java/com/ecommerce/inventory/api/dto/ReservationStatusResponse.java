package com.ecommerce.inventory.api.dto;

/** Answer to a reservation status query: RESERVED / COMMITTED / RELEASED / NOT_FOUND. */
public record ReservationStatusResponse(String orderId, String status) {
}
