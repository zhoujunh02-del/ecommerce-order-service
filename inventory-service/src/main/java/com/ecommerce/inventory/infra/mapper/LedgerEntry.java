package com.ecommerce.inventory.infra.mapper;

/** A projection of a stock_ledger row: which SKU, how many units. */
public record LedgerEntry(long skuId, int quantity) {
}
