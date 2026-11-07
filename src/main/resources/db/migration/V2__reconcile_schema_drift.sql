-- ---------------------------------------------------------------------------
-- Reconcile schema drift (Phase 2)
--
-- Context: before Phase 2 there were three competing descriptions of the
-- schema - this Flyway baseline, db/schema.sql (hand-written MySQL DDL) and
-- the live MySQL database. They had diverged in three places. This migration
-- resolves them in favour of the entities, and every change below was verified
-- against the live data before being written:
--
--   1. document_permissions.share_privilege - a legacy column that nothing
--      reads or writes. In the live database every one of the 2 rows held the
--      empty string, and no Java, SQL, JS or HTML file references it. The
--      entity defaulted it to "" on every insert purely to satisfy NOT NULL.
--      Dropped so the column cannot imply a sharing rule that does not exist.
--
--   2. audit_logs.outcome had no index, although the admin dashboard, the
--      DENIED counter and every audit filter query by it. The live MySQL
--      database had this index and this baseline did not.
--
--   3. document_share_restrictions existed in the live MySQL database with
--      zero rows and no entity, repository, service or endpoint anywhere in
--      the codebase. It described an administrator-restriction feature that
--      was never implemented. It is deliberately NOT created here: carrying a
--      table forward would imply a control that does not run. If that feature
--      is wanted later it belongs in a designed migration with code and tests,
--      not as an orphan table. The MySQL copy stays untouched as rollback.
--
-- Nothing in this migration rewrites or deletes existing rows.
-- ---------------------------------------------------------------------------

ALTER TABLE document_permissions
    DROP COLUMN share_privilege;

CREATE INDEX IF NOT EXISTS idx_audit_outcome ON audit_logs (outcome);
