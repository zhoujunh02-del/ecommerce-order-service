package com.ecommerce.order.infra.mapper;

import java.math.BigDecimal;

/** An order line as stored/read: SKU plus its price/name snapshot. */
public record OrderItemRow(long skuId, String skuName, BigDecimal unitPrice, int quantity) {
}
