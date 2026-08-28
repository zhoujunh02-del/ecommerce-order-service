# Architecture Decision Records

Each record captures one significant decision: the context, the option chosen, the
consequences, and — importantly — the alternatives that were rejected and why. They
are the reasoning behind the code.

| # | Decision |
|---|----------|
| [0001](0001-two-services-independent-databases.md) | Two services, each owning its database |
| [0002](0002-consistency-saga-outbox.md) | Synchronous reserve + Saga compensation + transactional outbox |
| [0003](0003-two-tier-stock-deduction.md) | Two-tier stock deduction (Redis + PostgreSQL) |
| [0004](0004-timeout-close-db-scan.md) | Timeout close via a database scan, not a queue delay |
| [0005](0005-idempotency-primary-key-claim.md) | Idempotency via a primary-key claim |
| [0006](0006-uuid-v7-primary-keys.md) | UUID v7 primary keys generated in the application |
