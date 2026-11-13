-- V2: make payment attempts a first-class reservation.
-- Flyway migrations are immutable, so V1 is never edited. Hibernate runs with
-- ddl-auto=validate, so this must exist before the context starts.

ALTER TABLE payments ADD COLUMN failure_code VARCHAR(64);

-- At most one open attempt per order. A failed/cancelled/refunded row falls out
-- of the partial index, so a retry is allowed.
CREATE UNIQUE INDEX payments_order_open_unique
    ON payments (order_id)
    WHERE status = 'PENDING';

-- At most one successful attempt per order, no matter how many retries happened.
CREATE UNIQUE INDEX payments_order_succeeded_unique
    ON payments (order_id)
    WHERE status = 'SUCCEEDED';

-- The sweeper scans only pending rows, so the index is partial too.
CREATE INDEX payments_pending_created_at_idx
    ON payments (created_at)
    WHERE status = 'PENDING';