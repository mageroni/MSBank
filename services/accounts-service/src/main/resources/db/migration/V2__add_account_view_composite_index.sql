-- Replace separate single-column indexes on account_view with a composite
-- covering index that matches the two most common query patterns:
--
--   1. WHERE customer_id = ?                     ORDER BY created_at
--   2. WHERE customer_id = ? AND status = ?      ORDER BY created_at
--
-- The old idx_account_view_customer_idx and idx_account_view_status_idx are
-- kept so that any ad-hoc queries filtering only on status still benefit from
-- an index.  The new composite index makes both queries above index-only scans
-- and eliminates the post-filter sort, which is the hot path on every
-- dashboard load, saga reservation check, and account-list request.

CREATE INDEX IF NOT EXISTS account_view_customer_status_created_idx
    ON account_view (customer_id, status, created_at);
