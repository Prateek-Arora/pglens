-- PgLens metadata schema, V1 (Phase 2). This is PgLens's OWN store (the metadata DB, never a monitored
-- DB). It is a real, growing, time-series-ish workload — and therefore the schema PgLens dogfoods its
-- own Tier-0 index tuning on (see docs/benchmarks.md). Correctness indexes (PKs, the validation-queue
-- operational indexes) live here from V1; the TIME-SERIES trend indexes are deliberately NOT added yet
-- so the dogfood exercise can measure a real before/after (see the note on query_stats below).
--
-- Design context: ADR-0024 (server-side ack-anchored deltas; reset via stats_since),
-- ADR-0023 (validation work-queue for the agent-pull topology).

-- Registry of the monitored databases this server collects from + per-agent auth (token is stored
-- hashed; the auth interceptor resolves a presented token to a monitored_dbs row — ADR-0027, Step 4).
CREATE TABLE monitored_dbs (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name             TEXT        NOT NULL UNIQUE,   -- logical identity the agent sends (SampleBatch.db_name)
    host             TEXT,                          -- host as the agent reports it (diagnostic)
    agent_token_hash TEXT        NOT NULL,          -- SHA-256 of the per-agent bearer token
    -- Last pg_stat_statements_info.stats_reset the server saw for this db. When an incoming batch
    -- reports a later value, every counter restarted (a global reset) → deltas are post-reset
    -- (ADR-0024). NULL until the first batch.
    last_stats_reset TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Dedup store of normalized query text + its captured generic plan, registered ONCE per queryid (the
-- per-interval samples carry only queryid + counters). queryid is pg_stat_statements' own stable hash
-- of the parse tree, so it is the natural key. plan_json is EXPLAIN (GENERIC_PLAN, VERBOSE, JSON);
-- plan_captured=false is a surfaced (not silent) skip for shapes that can't be generic-planned.
CREATE TABLE query_texts (
    db_id           BIGINT      NOT NULL REFERENCES monitored_dbs (id) ON DELETE CASCADE,
    queryid         BIGINT      NOT NULL,
    text_hash       TEXT        NOT NULL,
    normalized_text TEXT        NOT NULL,
    plan_json       TEXT,
    plan_captured   BOOLEAN     NOT NULL DEFAULT false,
    truncated       BOOLEAN     NOT NULL DEFAULT false,
    first_seen      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (db_id, queryid)
);

-- The delta ANCHOR: the last cumulative counters seen per query. The server computes each interval's
-- delta as (incoming cumulative − this row), then upserts this row. Anchoring to the last PERSISTED
-- cumulative is what makes ingest loss-tolerant with no double-counting (ADR-0024, plan §10).
CREATE TABLE query_cumulative (
    db_id              BIGINT           NOT NULL REFERENCES monitored_dbs (id) ON DELETE CASCADE,
    queryid            BIGINT           NOT NULL,
    calls              BIGINT           NOT NULL,
    total_exec_time_ms DOUBLE PRECISION NOT NULL,
    rows               BIGINT           NOT NULL,
    shared_blks_hit    BIGINT           NOT NULL,
    shared_blks_read   BIGINT           NOT NULL,
    captured_at        TIMESTAMPTZ      NOT NULL,   -- server receive time of this cumulative
    PRIMARY KEY (db_id, queryid)
);

-- The delta TIME-SERIES — the trend/leaderboard/top-movers source, and the dogfood indexing target.
-- Idempotent on the natural key so a retried batch can't double-insert (ADR-0024).
--
-- DOGFOOD NOTE: the PK (db_id, queryid, captured_at) serves per-query time scans, but a cross-query
-- "top movers this week" scans by captured_at range across ALL queryids — which this PK does NOT serve
-- well. That gap is intentional: the Phase 2 dogfood benchmark (docs/benchmarks.md) backfills weeks of
-- rows, measures that query with PgLens itself, then adds the right index (BRIN on captured_at for an
-- append-only column, or a composite) and records the real before/after. Do NOT pre-add it here.
CREATE TABLE query_stats (
    db_id                    BIGINT           NOT NULL REFERENCES monitored_dbs (id) ON DELETE CASCADE,
    queryid                  BIGINT           NOT NULL,
    captured_at              TIMESTAMPTZ      NOT NULL,   -- server receive time (the trend axis)
    agent_sample_at          TIMESTAMPTZ      NOT NULL,   -- agent wall-clock (clock-skew diagnostics)
    calls_delta              BIGINT           NOT NULL,
    total_exec_time_delta_ms DOUBLE PRECISION NOT NULL,
    mean_exec_time_ms        DOUBLE PRECISION,            -- derived = total_delta/calls_delta; NULL if calls_delta=0
    rows_delta               BIGINT           NOT NULL,
    shared_blks_hit_delta    BIGINT           NOT NULL,
    shared_blks_read_delta   BIGINT           NOT NULL,
    PRIMARY KEY (db_id, queryid, captured_at)
);

-- Existing-index catalog for a monitored DB's user tables (used by detection + the index-hygiene rule).
-- constraint_backed / is_unique / is_primary gate the hygiene advice: PgLens never recommends dropping
-- a unique/PK/constraint-backing index (B5 safety invariant).
CREATE TABLE index_catalog (
    db_id             BIGINT      NOT NULL REFERENCES monitored_dbs (id) ON DELETE CASCADE,
    index_name        TEXT        NOT NULL,
    table_name        TEXT        NOT NULL,
    definition        TEXT        NOT NULL,        -- pg_get_indexdef
    is_unique         BOOLEAN     NOT NULL,
    is_primary        BOOLEAN     NOT NULL,
    constraint_backed BOOLEAN     NOT NULL,
    first_seen        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (db_id, index_name)
);

-- Cumulative pg_stat_user_indexes.idx_scan snapshots. "Unused over the window" = idx_scan did not grow
-- between the window's first and last snapshot — honest only BECAUSE Phase 2 persists this history
-- (ADR-0029). Cumulative here (like query_cumulative); hygiene deltas it at query time.
CREATE TABLE index_stats (
    db_id       BIGINT      NOT NULL REFERENCES monitored_dbs (id) ON DELETE CASCADE,
    index_name  TEXT        NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    idx_scan    BIGINT      NOT NULL,   -- cumulative
    PRIMARY KEY (db_id, index_name, captured_at)
);

-- Validated (or suppressed / not-planner-validated) index recommendations with their HypoPG evidence.
-- One row per candidate DDL per query; re-analysis upserts (latest verdict wins). Every cost is a
-- labeled generic-plan planner estimate, never a runtime measurement (charter principle #1).
CREATE TABLE recommendations (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    db_id              BIGINT           NOT NULL REFERENCES monitored_dbs (id) ON DELETE CASCADE,
    queryid            BIGINT           NOT NULL,
    ddl                TEXT             NOT NULL,
    access_method      TEXT             NOT NULL,
    status             TEXT             NOT NULL,   -- ValidationStatus name (PLANNER_VALIDATED / ...)
    before_cost        DOUBLE PRECISION,
    after_cost         DOUBLE PRECISION,
    relative_drop      DOUBLE PRECISION,
    used               BOOLEAN,
    reason             TEXT,
    estimated_ms_saved DOUBLE PRECISION,            -- ranking score (labeled estimate, never "measured")
    created_at         TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ      NOT NULL DEFAULT now(),
    UNIQUE (db_id, queryid, ddl)
);

-- The validation WORK QUEUE (ADR-0023 agent-pull). The scheduled analysis job enqueues candidate DDLs
-- as PENDING; an agent leases a batch (FOR UPDATE SKIP LOCKED, Step 5), runs HypoPG at the edge, and
-- reports the verdict back. state: PENDING -> LEASED -> DONE | FAILED.
CREATE TABLE validation_jobs (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    db_id          BIGINT           NOT NULL REFERENCES monitored_dbs (id) ON DELETE CASCADE,
    queryid        BIGINT           NOT NULL,
    normalized_sql TEXT             NOT NULL,
    candidate_ddl  TEXT             NOT NULL,
    access_method  TEXT             NOT NULL,
    state          TEXT             NOT NULL DEFAULT 'PENDING',
    leased_at      TIMESTAMPTZ,
    created_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    -- result, populated on report:
    result_status  TEXT,
    before_cost    DOUBLE PRECISION,
    after_cost     DOUBLE PRECISION,
    relative_drop  DOUBLE PRECISION,
    used           BOOLEAN,
    reason         TEXT,
    completed_at   TIMESTAMPTZ
);

-- Operational indexes for the queue (NOT the dogfood target — these are correctness/latency indexes).
-- Partial index on PENDING keeps the lease scan tight; the partial UNIQUE prevents enqueuing a
-- duplicate candidate while one is already in flight for the same query.
CREATE INDEX validation_jobs_pending_idx
    ON validation_jobs (db_id, created_at)
    WHERE state = 'PENDING';

CREATE UNIQUE INDEX validation_jobs_inflight_uniq
    ON validation_jobs (db_id, queryid, candidate_ddl)
    WHERE state IN ('PENDING', 'LEASED');
