package com.ecommerce.inventory.job;

import com.ecommerce.inventory.domain.Inventory;
import com.ecommerce.inventory.infra.mapper.InventoryMapper;
import com.ecommerce.inventory.infra.redis.RedisStockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically re-syncs Redis to the database, which is the source of truth. Any
 * drift (from a failed refund, a Redis restart, a lost write) is corrected toward
 * the DB value. Also serves as warm-up: the first run populates Redis from the DB.
 */
@Component
public class StockConsistencyChecker {

    private static final Logger log = LoggerFactory.getLogger(StockConsistencyChecker.class);

    private final InventoryMapper inventoryMapper;
    private final RedisStockService redisStock;

    public StockConsistencyChecker(InventoryMapper inventoryMapper, RedisStockService redisStock) {
        this.inventoryMapper = inventoryMapper;
        this.redisStock = redisStock;
    }

    @Scheduled(fixedDelayString = "${inventory.redis.consistency-check-ms}")
    public void reconcile() {
        for (Inventory inv : inventoryMapper.findAll()) {
            Long redisValue = redisStock.peek(inv.skuId());
            if (redisValue == null || redisValue != inv.available()) {
                if (redisValue != null) {
                    log.warn("Redis stock drift for sku {}: redis={}, db={} — correcting to db",
                            inv.skuId(), redisValue, inv.available());
                }
                redisStock.sync(inv.skuId(), inv.available());
            }
        }
    }
}
