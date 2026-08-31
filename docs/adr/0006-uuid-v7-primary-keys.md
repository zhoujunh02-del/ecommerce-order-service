# 0006 — UUID v7 primary keys generated in the application

Status: accepted

## Context

Orders need a primary key that is globally unique (services and, eventually, shards
can generate ids independently), does not leak business volume, and does not hurt
index write performance.

## Decision

Use UUID version 7 (a 48-bit millisecond timestamp followed by random bits) for
`orders.id`, generated in the application. Because the high bits are time-ordered,
inserts land at the "right end" of the B-tree index instead of at random positions.
PostgreSQL 16 has no native `uuidv7()` (that arrived in PostgreSQL 18), so the
application supplies the value; a small MyBatis type handler binds `java.util.UUID`
to the `uuid` column.

## Consequences

- Time-ordered ids keep index inserts sequential, avoiding the page splits that
  random UUID v4 causes, while still being globally unique and coordination-free.
- Ids do not expose order volume, and generating them in the application means the id
  is known before insert (useful for logging and correlation).

## Alternatives considered

- **`BIGSERIAL` auto-increment (rejected):** leaks volume (two sequential ids reveal
  how many orders happened in between) and collides across shards.
- **UUID v4 random (rejected):** globally unique, but fully random keys scatter across
  the index and cause page splits and slower writes.
