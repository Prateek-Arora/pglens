-- The UTC hours in which each database's agent reported (Phase 4 Step 12, ADR-0052).
--
-- query_stats has no row for a query that didn't run, so an hour with no rows is either zero load
-- (the agent reported, nothing ran) or no data (the agent was down). The overview and the timelines
-- draw the first as zero and the second as a gap. One row per database per hour, written at ingest;
-- tiny, and kept like the hourly rollup.
CREATE TABLE agent_hours (
    db_id BIGINT      NOT NULL REFERENCES monitored_dbs (id) ON DELETE CASCADE,
    hour  TIMESTAMPTZ NOT NULL,   -- date_trunc('hour', server receive time, 'UTC')
    PRIMARY KEY (db_id, hour)
);

-- Backfill from the per-batch table snapshots already stored (appended on every ingest).
INSERT INTO agent_hours (db_id, hour)
SELECT DISTINCT db_id, date_trunc('hour', captured_at, 'UTC')
FROM table_stats;
