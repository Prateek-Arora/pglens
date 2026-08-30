package com.pglens.server.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The reads/writes the scheduled analysis job needs: the queries whose plan was captured (its
 * input) and the validation work-queue enqueue (its output). Recommendation persistence + leasing
 * land in Step 5b.
 */
@Repository
public class AnalysisRepository {

  private final JdbcTemplate jdbc;

  public AnalysisRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * One captured query the detector can run over: its normalized text and its generic plan JSON.
   */
  public record AnalyzableQuery(long queryid, String normalizedText, String planJson) {}

  /**
   * Queries for this db that have a captured generic plan (the only ones detection can analyze).
   */
  public List<AnalyzableQuery> planCapturedQueries(long dbId) {
    return jdbc.query(
        "SELECT queryid, normalized_text, plan_json FROM query_texts "
            + "WHERE db_id = ? AND plan_captured = true AND plan_json IS NOT NULL "
            + "ORDER BY queryid",
        (rs, n) ->
            new AnalyzableQuery(
                rs.getLong("queryid"), rs.getString("normalized_text"), rs.getString("plan_json")),
        dbId);
  }

  /**
   * Enqueues a candidate DDL as a PENDING validation job, unless (a) an identical candidate is
   * already in flight (PENDING/LEASED) — the partial UNIQUE {@code validation_jobs_inflight_uniq}
   * is the arbiter — or (b) it was already validated recently, i.e. a recommendation for this exact
   * candidate has {@code updated_at} at or after {@code revalidateBefore} (the analysis cooldown,
   * F1). Without (b), every pass would re-enqueue a fresh job for an already-validated candidate
   * the instant its prior job went DONE, growing the queue unboundedly and re-running edge HypoPG
   * every tick. A candidate whose recommendation has aged past the cooldown IS re-enqueued, so a
   * stale verdict still gets refreshed (e.g. retracted once the user actually adds the index).
   * Returns 1 if enqueued, 0 if skipped.
   */
  public int enqueue(
      long dbId,
      long queryid,
      String normalizedSql,
      String candidateDdl,
      String accessMethod,
      Instant revalidateBefore) {
    return jdbc.update(
        "INSERT INTO validation_jobs (db_id, queryid, normalized_sql, candidate_ddl, access_method) "
            + "SELECT ?, ?, ?, ?, ? "
            + "WHERE NOT EXISTS ("
            + "  SELECT 1 FROM recommendations r "
            + "  WHERE r.db_id = ? AND r.queryid = ? AND r.ddl = ? AND r.updated_at >= ?) "
            + "ON CONFLICT (db_id, queryid, candidate_ddl) WHERE state IN ('PENDING', 'LEASED') "
            + "DO NOTHING",
        dbId,
        queryid,
        normalizedSql,
        candidateDdl,
        accessMethod,
        dbId,
        queryid,
        candidateDdl,
        utc(revalidateBefore));
  }

  /**
   * Deletes terminal (DONE/FAILED) validation jobs completed before {@code completedBefore},
   * bounding the work-queue's growth (F1). Each job's verdict is persisted separately in {@code
   * recommendations}, so pruning the finished job loses no advice — only the queue's audit trail.
   * Runs inside the singleton analysis transaction. Returns the number of rows removed.
   */
  public int pruneTerminalJobs(Instant completedBefore) {
    return jdbc.update(
        "DELETE FROM validation_jobs WHERE state IN ('DONE', 'FAILED') AND completed_at < ?",
        utc(completedBefore));
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
