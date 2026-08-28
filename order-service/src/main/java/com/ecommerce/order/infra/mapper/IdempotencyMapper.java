package com.ecommerce.order.infra.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IdempotencyMapper {

    /**
     * Claim the key by inserting an IN_PROGRESS row. Throws DuplicateKeyException
     * if the key already exists — that conflict IS the duplicate-request detector.
     */
    int insertClaim(@Param("idemKey") String idemKey, @Param("requestHash") String requestHash);

    IdempotencyRow find(@Param("idemKey") String idemKey);

    /** Store the final outcome and flip the row to COMPLETED. */
    int complete(@Param("idemKey") String idemKey, @Param("response") String response);
}
