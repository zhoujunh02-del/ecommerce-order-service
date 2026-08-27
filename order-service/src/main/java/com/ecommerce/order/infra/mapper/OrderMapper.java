package com.ecommerce.order.infra.mapper;

import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderMapper {

    int insert(Order order);

    /**
     * Conditional state transition. Returns rows affected: 1 = this caller won the
     * transition, 0 = the order was not in {@code from} (someone else moved it first).
     */
    int transition(@Param("id") UUID id,
                   @Param("from") OrderStatus from,
                   @Param("to") OrderStatus to,
                   @Param("reason") String reason);

    Order findById(@Param("id") UUID id);

    Order findByIdAndUser(@Param("id") UUID id, @Param("userId") long userId);

    /** Keyset page: rows strictly "older" than the cursor, newest first. */
    List<Order> findPage(@Param("userId") long userId,
                         @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
                         @Param("cursorId") UUID cursorId,
                         @Param("size") int size);
}
