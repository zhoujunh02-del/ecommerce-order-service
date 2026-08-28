package com.ecommerce.inventory.infra.redis;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * The performance layer. Every method fails SAFE: if Redis is disabled, missing a
 * key, or unreachable, pre-deduction returns DEGRADE so the caller falls back to
 * the database (the source of truth). Redis can make us under-sell, never oversell.
 */
@Service
public class RedisStockService {

    /** Pre-deduction sentinels (also see lua/deduct.lua). */
    public static final long DEGRADE = -2;
    public static final long INSUFFICIENT = -1;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> deductScript;
    private final boolean enabled;

    public RedisStockService(StringRedisTemplate redis,
                             RedisScript<Long> deductScript,
                             @Value("${inventory.redis.enabled}") boolean enabled) {
        this.redis = redis;
        this.deductScript = deductScript;
        this.enabled = enabled;
    }

    /** @return remaining stock (>=0), INSUFFICIENT, or DEGRADE (go to the database). */
    public long preDeduct(long skuId, int qty) {
        if (!enabled) {
            return DEGRADE;
        }
        try {
            Long result = redis.execute(deductScript, List.of(key(skuId)), String.valueOf(qty));
            return result == null ? DEGRADE : result;
        } catch (RuntimeException e) {
            return DEGRADE;   // Redis down → degrade to the database
        }
    }

    /** Give back stock pre-deducted in Redis when the database later rejects/releases it. */
    public void refund(long skuId, long qty) {
        if (!enabled) {
            return;
        }
        try {
            redis.opsForValue().increment(key(skuId), qty);
        } catch (RuntimeException ignored) {
            // Tolerable: Redis ends up smaller than the DB → at worst we under-sell,
            // and the consistency checker will correct it.
        }
    }

    /** Overwrite Redis with the authoritative database value (used by warm-up and the checker). */
    public void sync(long skuId, long available) {
        if (!enabled) {
            return;
        }
        try {
            redis.opsForValue().set(key(skuId), Long.toString(available));
        } catch (RuntimeException ignored) {
        }
    }

    public Long peek(long skuId) {
        if (!enabled) {
            return null;
        }
        try {
            String value = redis.opsForValue().get(key(skuId));
            return value == null ? null : Long.parseLong(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String key(long skuId) {
        return "stock:" + skuId;
    }
}
