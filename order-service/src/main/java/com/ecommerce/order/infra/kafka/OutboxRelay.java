package com.ecommerce.order.infra.kafka;

import com.ecommerce.order.infra.mapper.OutboxMapper;
import com.ecommerce.order.infra.mapper.OutboxRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes outbox events to Kafka. Runs as a background job — never on the request
 * path — and does NOT hold a database transaction across the Kafka send: it reads a
 * batch, publishes each (waiting for the broker ack), then marks it sent. If it
 * crashes after publish but before markSent, the event is republished later; that
 * duplicate is fine because consumers are idempotent (at-least-once delivery).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxMapper outboxMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final int batchSize;
    private final int maxRetry;

    public OutboxRelay(OutboxMapper outboxMapper,
                       KafkaTemplate<String, String> kafkaTemplate,
                       @Value("${outbox.topic}") String topic,
                       @Value("${outbox.batch-size}") int batchSize,
                       @Value("${outbox.max-retry}") int maxRetry) {
        this.outboxMapper = outboxMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.batchSize = batchSize;
        this.maxRetry = maxRetry;
    }

    @Scheduled(fixedDelayString = "${outbox.relay-interval-ms}")
    public void relay() {
        List<OutboxRow> batch = outboxMapper.findBatchToSend(batchSize);
        for (OutboxRow row : batch) {
            try {
                // Key = order id, so all events for one order go to the same partition
                // and stay ordered. .get() waits for the broker ack before we mark sent.
                kafkaTemplate.send(topic, row.aggregateId().toString(), row.payload())
                        .get(3, TimeUnit.SECONDS);
                outboxMapper.markSent(row.id());
            } catch (Exception e) {
                int nextAttempt = row.retryCount() + 1;
                if (nextAttempt >= maxRetry) {
                    outboxMapper.markDead(row.id());
                    log.error("Outbox event {} moved to DEAD after {} retries", row.id(), nextAttempt, e);
                } else {
                    // Exponential backoff, capped.
                    long backoffSec = Math.min(60, (long) Math.pow(2, nextAttempt));
                    outboxMapper.markRetry(row.id(), OffsetDateTime.now().plusSeconds(backoffSec));
                    log.warn("Outbox event {} publish failed (attempt {}), retrying in {}s",
                            row.id(), nextAttempt, backoffSec);
                }
            }
        }
    }
}
