-- V6 (Phase 2.5, ADR-0038). Recommendation-accuracy evidence + a table read/write time-series.
--
-- 1. recommendations gain the evidence the edge validator now reports for a PLANNER_VALIDATED rec:
--    - a VALUE RANGE: the query re-planned with real pg_stats values for its equality parameters.
--      Only drops and the worst value's FREQUENCY are stored — the sampled values are real data and
--      never leave the agent.
--    - a FOOTPRINT: HypoPG's size estimate for the index and the table's heap size (write-cost proxy).
--    - score_basis: which drop estimated_ms_saved was computed from (VALUE_RANGE_FLOOR / GENERIC_PLAN).
--    Every number stays a labeled planner estimate (charter #1); NULL means "not measured", never 0.
ALTER TABLE recommendations ADD COLUMN range_column          TEXT;
ALTER TABLE recommendations ADD COLUMN range_values_sampled  INTEGER;
ALTER TABLE recommendations ADD COLUMN range_worst_drop      DOUBLE PRECISION;
ALTER TABLE recommendations ADD COLUMN range_worst_frequency DOUBLE PRECISION;
ALTER TABLE recommendations ADD COLUMN range_best_drop       DOUBLE PRECISION;
ALTER TABLE recommendations ADD COLUMN range_label           TEXT;
ALTER TABLE recommendations ADD COLUMN est_index_bytes       BIGINT;
ALTER TABLE recommendations ADD COLUMN table_bytes           BIGINT;
ALTER TABLE recommendations ADD COLUMN footprint_label       TEXT;
ALTER TABLE recommendations ADD COLUMN score_basis           TEXT;

-- 2. table_stats: CUMULATIVE pg_stat_user_tables counters per table, appended each ingest (like
--    index_stats). The server deltas the first vs last snapshot in a window for the write-load note;
--    a backwards counter is a stats reset, so that window is inconclusive, never "no writes".
CREATE TABLE table_stats (
    db_id       BIGINT      NOT NULL REFERENCES monitored_dbs (id) ON DELETE CASCADE,
    table_name  TEXT        NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    n_tup_ins   BIGINT      NOT NULL,
    n_tup_upd   BIGINT      NOT NULL,
    n_tup_del   BIGINT      NOT NULL,
    tuples_read BIGINT      NOT NULL,   -- seq_tup_read + idx_tup_fetch
    PRIMARY KEY (db_id, table_name, captured_at)
);
