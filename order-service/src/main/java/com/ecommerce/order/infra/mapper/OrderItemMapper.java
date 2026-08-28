package com.ecommerce.order.infra.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderItemMapper {

    int batchInsert(@Param("orderId") UUID orderId, @Param("items") List<OrderItemRow> items);

    List<OrderItemRow> findByOrderId(@Param("orderId") UUID orderId);
}
