-- V2 (Phase 2, Step 5): the table row-estimate store the server-side detector needs.
--
-- AntiPatternDetector.detect(plan, catalog) reasons over a CatalogSnapshot = per-table reltuples +
-- existing indexes. V1 added index_catalog (the indexes); this adds the table estimates so the server
-- can rebuild the snapshot from persisted state and run the pure detection pipeline on a schedule
-- (ADR-0028). reltuples is the planner's own live-row estimate (a labeled estimate, never a measured
-- count — charter #1); -1 means never analyzed / unknown.
CREATE TABLE table_catalog (
    db_id      BIGINT      NOT NULL REFERENCES monitored_dbs (id) ON DELETE CASCADE,
    table_name TEXT        NOT NULL,
    est_rows   BIGINT      NOT NULL,   -- reltuples estimate as of the last catalog snapshot
    first_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (db_id, table_name)
);

-- V1's index_catalog carries the hygiene columns (definition / constraint_backed) which Step 6 will
-- populate for real; Step 5 needs the detection inputs (ordered key columns, access method, partial
-- predicate) alongside them. Columns are stored as a text[] (ordered).
ALTER TABLE index_catalog ADD COLUMN columns   TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE index_catalog ADD COLUMN method    TEXT   NOT NULL DEFAULT 'btree';
ALTER TABLE index_catalog ADD COLUMN predicate TEXT;
