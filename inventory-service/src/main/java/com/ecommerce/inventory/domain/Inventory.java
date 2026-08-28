package com.ecommerce.inventory.domain;

/**
 * Three-state stock for one SKU. Column order matches this constructor so
 * MyBatis can map SELECT results onto the record positionally.
 */
public record Inventory(long skuId, int available, int reserved, int sold, int version) {
}
