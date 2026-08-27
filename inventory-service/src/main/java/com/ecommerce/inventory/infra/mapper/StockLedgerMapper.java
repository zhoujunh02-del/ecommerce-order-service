package com.ecommerce.inventory.infra.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockLedgerMapper {

    /**
     * Append a ledger row. The UNIQUE (order_id, sku_id, op_type) constraint makes
     * a duplicate op fail here, which is how stock operations become idempotent.
     */
    int insert(@Param("skuId") long skuId,
               @Param("orderId") UUID orderId,
               @Param("opType") String opType,
               @Param("qty") int qty);

    List<LedgerEntry> findByOrderAndOp(@Param("orderId") UUID orderId,
                                       @Param("opType") String opType);
}
