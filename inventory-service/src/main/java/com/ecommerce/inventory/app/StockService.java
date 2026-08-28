package com.ecommerce.inventory.app;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.common.error.ErrorCode;
import com.ecommerce.inventory.api.dto.ReserveLine;
import com.ecommerce.inventory.domain.Inventory;
import com.ecommerce.inventory.infra.mapper.InventoryMapper;
import com.ecommerce.inventory.infra.mapper.LedgerEntry;
import com.ecommerce.inventory.infra.mapper.StockLedgerMapper;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Three-state stock operations. Each method is a single local transaction; none
 * of them performs any network I/O.
 */
@Service
public class StockService {

    private final InventoryMapper inventoryMapper;
    private final StockLedgerMapper ledgerMapper;

    public StockService(InventoryMapper inventoryMapper, StockLedgerMapper ledgerMapper) {
        this.inventoryMapper = inventoryMapper;
        this.ledgerMapper = ledgerMapper;
    }

    /** Hold stock for an order. available -> reserved. */
    @Transactional
    public void reserve(UUID orderId, List<ReserveLine> lines) {
        List<String> ops = ledgerMapper.findOpTypes(orderId);
        if (ops.contains("RELEASE")) {
            // The order was already released/compensated. A late reserve arriving now
            // is the "hanging" problem — reserving would leak stock. Reject it.
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT,
                    "reservation already released for order " + orderId);
        }
        if (ops.contains("RESERVE")) {
            return;   // idempotent: a retry of an already-successful reserve
        }

        // Lock rows in a stable order (by sku_id) so two multi-SKU orders touching
        // the same SKUs in different request order cannot deadlock each other.
        List<ReserveLine> ordered = lines.stream()
                .sorted(Comparator.comparingLong(ReserveLine::skuId))
                .toList();

        for (ReserveLine line : ordered) {
            int affected = inventoryMapper.deductAvailable(line.skuId(), line.quantity());
            if (affected == 0) {
                // Either the SKU doesn't exist or there isn't enough stock. Rolling
                // back here undoes any earlier lines reserved in this same order.
                throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK,
                        "insufficient stock for sku " + line.skuId());
            }
            ledgerMapper.insert(line.skuId(), orderId, "RESERVE", line.quantity());
        }
    }

    /** Confirm a paid order's stock. reserved -> sold. Quantities come from the ledger. */
    @Transactional
    public void commit(UUID orderId) {
        // Idempotent: Kafka delivers at-least-once, so a redelivered OrderPaid must
        // be a no-op. If we already committed this order, stop.
        if (!ledgerMapper.findByOrderAndOp(orderId, "COMMIT").isEmpty()) {
            return;
        }
        for (LedgerEntry r : ledgerMapper.findByOrderAndOp(orderId, "RESERVE")) {
            int affected = inventoryMapper.commitReserved(r.skuId(), r.quantity());
            if (affected == 0) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "commit inconsistency for sku " + r.skuId());
            }
            ledgerMapper.insert(r.skuId(), orderId, "COMMIT", r.quantity());
        }
    }

    /** Return a cancelled/timed-out order's stock. reserved -> available. */
    @Transactional
    public void release(UUID orderId) {
        List<String> ops = ledgerMapper.findOpTypes(orderId);
        if (ops.contains("RELEASE")) {
            return;   // already released (redelivered OrderCancelled) — no-op
        }
        if (!ops.contains("RESERVE")) {
            // EMPTY ROLLBACK: a release arrived but no reservation exists (the reserve
            // request was lost, or never happened). Add no stock, but record a RELEASE
            // marker (sku_id 0) so that a late reserve for this order is rejected.
            ledgerMapper.insert(0L, orderId, "RELEASE", 0);
            return;
        }
        for (LedgerEntry r : ledgerMapper.findByOrderAndOp(orderId, "RESERVE")) {
            int affected = inventoryMapper.releaseReserved(r.skuId(), r.quantity());
            if (affected == 0) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "release inconsistency for sku " + r.skuId());
            }
            ledgerMapper.insert(r.skuId(), orderId, "RELEASE", r.quantity());
        }
    }

    /**
     * The reservation status for an order, derived from the ledger. This is the
     * endpoint the order-service reconciler calls to resolve a reserve whose outcome
     * it never learned (e.g. a timed-out reserve). It is what lets a Saga converge.
     */
    @Transactional(readOnly = true)
    public String queryReservation(UUID orderId) {
        List<String> ops = ledgerMapper.findOpTypes(orderId);
        if (ops.contains("RELEASE")) {
            return "RELEASED";
        }
        if (ops.contains("COMMIT")) {
            return "COMMITTED";
        }
        if (ops.contains("RESERVE")) {
            return "RESERVED";
        }
        return "NOT_FOUND";
    }

    @Transactional(readOnly = true)
    public Inventory getInventory(long skuId) {
        Inventory inv = inventoryMapper.findBySkuId(skuId);
        if (inv == null) {
            throw new BusinessException(ErrorCode.SKU_NOT_FOUND, "no such sku " + skuId);
        }
        return inv;
    }
}
