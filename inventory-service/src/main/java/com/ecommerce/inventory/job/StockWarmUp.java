package com.ecommerce.inventory.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Loads DB stock into Redis at startup so the performance layer is ready immediately. */
@Component
public class StockWarmUp implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StockWarmUp.class);

    private final StockConsistencyChecker checker;

    public StockWarmUp(StockConsistencyChecker checker) {
        this.checker = checker;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            checker.reconcile();
            log.info("Redis stock warm-up complete");
        } catch (RuntimeException e) {
            log.warn("Redis stock warm-up failed (will run degraded until Redis is back): {}", e.getMessage());
        }
    }
}
