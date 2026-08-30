-- V3 (Phase 2, Step 6): persisted index-hygiene findings (B5 / ADR-0029).
--
-- The scheduled analysis (ADR-0028) reads each db's index_catalog + the idx_scan history in
-- index_stats, runs the pure IndexHygieneAnalyzer, and REPLACES this db's findings here (one row per
-- flagged index — at most one finding per index, priority DUPLICATE > REDUNDANT > UNUSED). Advisory
-- only: PgLens never auto-drops an index, and by construction a unique/PK/FK/constraint-backing index
-- is never flagged (the B5 safety invariant lives in the analyzer). Findings are derived state,
-- recomputed every pass, so they are replaced (not history) — a since-fixed index stops appearing.
CREATE TABLE index_hygiene (
    db_id         BIGINT      NOT NULL REFERENCES monitored_dbs (id) ON DELETE CASCADE,
    index_name    TEXT        NOT NULL,
    table_name    TEXT        NOT NULL,
    kind          TEXT        NOT NULL,          -- DUPLICATE | REDUNDANT | UNUSED
    related_index TEXT,                          -- the surviving/covering index; NULL for UNUSED
    definition    TEXT        NOT NULL,          -- pg_get_indexdef, for display
    reason        TEXT        NOT NULL,          -- evidence sentence from real catalog/window facts
    detected_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (db_id, index_name)
);
