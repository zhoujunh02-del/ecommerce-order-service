package com.ecommerce.order.infra.mapper;

import java.math.BigDecimal;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PaymentMapper {

    /** Record a suspicious/unmatched callback for later investigation. */
    int insertAnomaly(@Param("orderId") UUID orderId,
                      @Param("payNo") String payNo,
                      @Param("amount") BigDecimal amount,
                      @Param("reason") String reason);

    /** Record that money was received for an order that is no longer payable. */
    int insertPendingRefund(@Param("orderId") UUID orderId,
                            @Param("payNo") String payNo,
                            @Param("amount") BigDecimal amount);

    long countPendingRefunds();
}
