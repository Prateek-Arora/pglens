-- Hourly rollup of query_stats (Phase 4 Step 5, ADR-0045). The read API's windowed reads — the
-- leaderboard, top movers, new slow queries — summed every raw 5-minute delta in the window: on a
-- 30-day, 500-query history (4.3 M rows, 749 MB) the 30-day leaderboard took 741 ms p95 over HTTP
-- (budget: 300 ms). Summing hourly buckets reads ~12x fewer rows (docs/benchmarks.md).
--
-- The rollup is maintained by a statement-level trigger, so every insert path (ingest, tests,
-- backfills) keeps it exact: the trigger sees only the rows actually inserted — a within-batch
-- duplicate skipped by query_stats' ON CONFLICT DO NOTHING is not counted. query_stats is
-- append-only (rows are never updated; they go only with their monitored_dbs row, and so does the
-- rollup), so sum(query_stats) = sum(query_stats_hourly) per (db, query, hour) always holds.
-- Buckets are UTC hours, independent of the session time zone.

CREATE TABLE query_stats_hourly (
    db_id              BIGINT           NOT NULL REFERENCES monitored_dbs (id) ON DELETE CASCADE,
    queryid            BIGINT           NOT NULL,
    hour               TIMESTAMPTZ      NOT NULL,   -- date_trunc('hour', captured_at, 'UTC')
    calls              BIGINT           NOT NULL,
    total_exec_time_ms DOUBLE PRECISION NOT NULL,
    rows               BIGINT           NOT NULL,
    shared_blks_hit    BIGINT           NOT NULL,
    shared_blks_read   BIGINT           NOT NULL,
    PRIMARY KEY (db_id, queryid, hour)
) WITH (fillfactor = 80);   -- room for the in-place (HOT) updates each new sample makes

-- Windows scan a time range across all queries; new hours are appended in time order, so a BRIN
-- index fits (as for query_stats itself, ADR-0033).
CREATE INDEX query_stats_hourly_hour_brin ON query_stats_hourly USING brin (hour);

INSERT INTO query_stats_hourly (db_id, queryid, hour, calls, total_exec_time_ms, rows,
                                shared_blks_hit, shared_blks_read)
SELECT db_id, queryid, date_trunc('hour', captured_at, 'UTC'),
       sum(calls_delta), sum(total_exec_time_delta_ms), sum(rows_delta),
       sum(shared_blks_hit_delta), sum(shared_blks_read_delta)
FROM query_stats
GROUP BY 1, 2, 3
ORDER BY 3;

CREATE FUNCTION query_stats_rollup() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO query_stats_hourly AS h (db_id, queryid, hour, calls, total_exec_time_ms, rows,
                                         shared_blks_hit, shared_blks_read)
    SELECT db_id, queryid, date_trunc('hour', captured_at, 'UTC'),
           sum(calls_delta), sum(total_exec_time_delta_ms), sum(rows_delta),
           sum(shared_blks_hit_delta), sum(shared_blks_read_delta)
    FROM new_rows
    GROUP BY 1, 2, 3
    ORDER BY 1, 2, 3   -- a fixed lock order: concurrent batches can't deadlock on the buckets
    ON CONFLICT (db_id, queryid, hour) DO UPDATE SET
        calls              = h.calls + excluded.calls,
        total_exec_time_ms = h.total_exec_time_ms + excluded.total_exec_time_ms,
        rows               = h.rows + excluded.rows,
        shared_blks_hit    = h.shared_blks_hit + excluded.shared_blks_hit,
        shared_blks_read   = h.shared_blks_read + excluded.shared_blks_read;
    RETURN NULL;
END;
$$;

CREATE TRIGGER query_stats_rollup
    AFTER INSERT ON query_stats
    REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION query_stats_rollup();
