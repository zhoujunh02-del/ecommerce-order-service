package com.ecommerce.order.infra.mapper;

import java.math.BigDecimal;

/** A row from the static product catalog. */
public record Sku(long id, String name, BigDecimal price) {
}
