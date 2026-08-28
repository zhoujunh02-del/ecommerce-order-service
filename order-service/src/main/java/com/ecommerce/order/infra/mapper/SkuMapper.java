package com.ecommerce.order.infra.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SkuMapper {

    List<Sku> findByIds(@Param("ids") List<Long> ids);
}
