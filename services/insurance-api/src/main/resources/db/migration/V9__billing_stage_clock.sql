-- Statement wall-clock timestamps preserve real per-stage timing within a transaction.
ALTER TABLE partner_batch ALTER COLUMN created_at SET DEFAULT clock_timestamp();
ALTER TABLE partner_batch ALTER COLUMN updated_at SET DEFAULT clock_timestamp();
ALTER TABLE billing_transition ALTER COLUMN created_at SET DEFAULT clock_timestamp();
