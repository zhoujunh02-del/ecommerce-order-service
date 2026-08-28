package com.ecommerce.inventory.infra.mapper;

import com.ecommerce.inventory.domain.Inventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InventoryMapper {

    /** Atomically deduct available stock. Returns rows affected (1 = ok, 0 = insufficient). */
    int deductAvailable(@Param("skuId") long skuId, @Param("qty") int qty);

    /** reserved -> sold on payment. */
    int commitReserved(@Param("skuId") long skuId, @Param("qty") int qty);

    /** reserved -> available on cancel/timeout. */
    int releaseReserved(@Param("skuId") long skuId, @Param("qty") int qty);

    Inventory findBySkuId(@Param("skuId") long skuId);
}
