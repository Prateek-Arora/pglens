package com.pglens.server.persistence;

import com.pglens.proto.v1.QueryText;
import com.pglens.server.ingest.CumulativeCounters;
import com.pglens.server.ingest.DeltaCalculator.Delta;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the ingest path: the query-text dedup store, the cumulative delta-anchor, and the
 * delta time-series (ADR-0024, ADR-0026). All timestamps are written as {@code timestamptz}
 * (OffsetDateTime at UTC). The delta insert is idempotent on {@code (db_id, queryid, captured_at)}
 * so a retried batch can't double-count.
 */
@Repository
public class SnapshotRepository {

  private final JdbcTemplate jdbc;

  public SnapshotRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The last cumulative counters persisted for this query (the delta anchor), if any. */
  public Optional<CumulativeCounters> lastCumulative(long dbId, long queryid) {
    return jdbc
        .query(
            """
            SELECT calls, total_exec_time_ms, rows, shared_blks_hit, shared_blks_read
            FROM query_cumulative WHERE db_id = ? AND queryid = ?
            """,
            (rs, n) ->
                new CumulativeCounters(
                    rs.getLong("calls"),
                    rs.getDouble("total_exec_time_ms"),
                    rs.getLong("rows"),
                    rs.getLong("shared_blks_hit"),
                    rs.getLong("shared_blks_read")),
            dbId,
            queryid)
        .stream()
        .findFirst();
  }

  /** Upserts the delta anchor to the latest cumulative counters. */
  public void upsertCumulative(long dbId, long queryid, CumulativeCounters c, Instant capturedAt) {
    jdbc.update(
        """
        INSERT INTO query_cumulative
          (db_id, queryid, calls, total_exec_time_ms, rows, shared_blks_hit, shared_blks_read,
           captured_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (db_id, queryid) DO UPDATE SET
          calls = EXCLUDED.calls,
          total_exec_time_ms = EXCLUDED.total_exec_time_ms,
          rows = EXCLUDED.rows,
          shared_blks_hit = EXCLUDED.shared_blks_hit,
          shared_blks_read = EXCLUDED.shared_blks_read,
          captured_at = EXCLUDED.captured_at
        """,
        dbId,
        queryid,
        c.calls(),
        c.totalExecTimeMs(),
        c.rows(),
        c.sharedBlksHit(),
        c.sharedBlksRead(),
        utc(capturedAt));
  }

  /** Appends one interval delta to the time-series; idempotent on the natural key. */
  public void insertDelta(
      long dbId, long queryid, Instant capturedAt, Instant agentSampleAt, Delta d) {
    jdbc.update(
        """
        INSERT INTO query_stats
          (db_id, queryid, captured_at, agent_sample_at, calls_delta, total_exec_time_delta_ms,
           mean_exec_time_ms, rows_delta, shared_blks_hit_delta, shared_blks_read_delta)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (db_id, queryid, captured_at) DO NOTHING
        """,
        dbId,
        queryid,
        utc(capturedAt),
        utc(agentSampleAt),
        d.callsDelta(),
        d.totalExecTimeDeltaMs(),
        d.meanExecTimeMs(),
        d.rowsDelta(),
        d.sharedBlksHitDelta(),
        d.sharedBlksReadDelta());
  }

  /** Registers (or refreshes) the normalized text + captured plan for a query. */
  public void upsertQueryText(long dbId, QueryText t) {
    jdbc.update(
        """
        INSERT INTO query_texts
          (db_id, queryid, text_hash, normalized_text, plan_json, plan_captured, truncated, last_seen)
        VALUES (?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (db_id, queryid) DO UPDATE SET
          text_hash = EXCLUDED.text_hash,
          normalized_text = EXCLUDED.normalized_text,
          plan_json = EXCLUDED.plan_json,
          plan_captured = EXCLUDED.plan_captured,
          truncated = EXCLUDED.truncated,
          last_seen = now()
        """,
        dbId,
        t.getQueryid(),
        t.getTextHash(),
        t.getNormalizedText(),
        t.getPlanJson().isEmpty() ? null : t.getPlanJson(),
        t.getPlanCaptured(),
        t.getTruncated());
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
