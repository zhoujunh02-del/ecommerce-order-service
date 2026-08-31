package com.ecommerce.order.infra.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OutboxMapper {

    /** Insert an event. Called INSIDE the same transaction as the state change. */
    int insert(@Param("aggregateId") UUID aggregateId,
               @Param("eventType") String eventType,
               @Param("payload") String payload);

    /** PENDING events whose backoff has elapsed, oldest first. */
    List<OutboxRow> findBatchToSend(@Param("limit") int limit);

    int markSent(@Param("id") long id);

    /** Bump retry_count and schedule the next attempt (stays PENDING). */
    int markRetry(@Param("id") long id, @Param("nextRetryAt") OffsetDateTime nextRetryAt);

    /** Give up after too many retries: move to DEAD for manual intervention. */
    int markDead(@Param("id") long id);

    long countByStatus(@Param("status") String status);
}
