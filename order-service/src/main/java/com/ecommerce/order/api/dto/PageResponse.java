package com.ecommerce.order.api.dto;

import java.util.List;

/** A page of results plus an opaque cursor for the next page (null = last page). */
public record PageResponse<T>(List<T> items, String nextCursor) {
}
