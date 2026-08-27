-- ============================================================================
-- inventory-service schema (inventory_db)
-- ============================================================================

-- ── inventory: three-state stock per SKU ────────────────────────────────────
--   available : sellable on the shelf
--   reserved  : held for orders that are placed but not yet paid
--   sold      : committed after payment
-- ★ Every column has CHECK (>= 0). This is the LAST line of defence: even if the
--   application logic has a bug, the database physically refuses to go negative.
--   Always keep one unbreakable constraint at the lowest layer.
CREATE TABLE inventory (
    sku_id     BIGINT      PRIMARY KEY,
    available  INT         NOT NULL CHECK (available >= 0),
    reserved   INT         NOT NULL CHECK (reserved  >= 0),
    sold       INT         NOT NULL CHECK (sold      >= 0),
    version    INT         NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── stock_ledger: append-only, immutable history of every stock operation ───
-- ★★ UNIQUE (order_id, sku_id, op_type) makes stock operations idempotent with
--    ONE constraint. A duplicate RESERVE/COMMIT/RELEASE (Saga retry, redelivered
--    Kafka message) violates the constraint on insert and is treated as
--    "already processed". A database constraint is atomic; an app-level `if` is not.
-- It also enables reconciliation: the ledger aggregate must equal the inventory
-- aggregate, and any mismatch is a bug.
CREATE TABLE stock_ledger (
    id         BIGSERIAL   PRIMARY KEY,
    sku_id     BIGINT      NOT NULL,
    order_id   UUID        NOT NULL,
    op_type    VARCHAR(10) NOT NULL
               CHECK (op_type IN ('RESERVE','COMMIT','RELEASE')),
    quantity   INT         NOT NULL,              -- 0 is allowed: an empty-rollback marker
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ledger_op UNIQUE (order_id, sku_id, op_type)
);
CREATE INDEX idx_ledger_order ON stock_ledger (order_id);
