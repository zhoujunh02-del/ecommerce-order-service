-- Store the idempotency key on the order so the reconciler can complete the
-- matching idempotency record when it resolves an order that was left CREATING.
ALTER TABLE orders ADD COLUMN idem_key VARCHAR(64);
