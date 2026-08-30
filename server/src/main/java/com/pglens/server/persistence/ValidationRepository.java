package com.pglens.server.persistence;

import com.pglens.proto.v1.ValidateResult;
import com.pglens.proto.v1.ValidationStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The validation work-queue reads/writes for the a-pull round-trip (ADR-0023): lease PENDING jobs
 * to an agent, and record the verdict the agent reports (persist the recommendation + close the
 * job).
 */
@Repository
public class ValidationRepository {

  private final JdbcTemplate jdbc;

  public ValidationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** A leased job the agent will HypoPG-validate at the edge. */
  public record LeasedJob(
      long id, long queryid, String normalizedSql, String candidateDdl, String accessMethod) {}

  /**
   * Atomically leases up to {@code max} PENDING jobs for this db and returns them. {@code FOR
   * UPDATE SKIP LOCKED} lets concurrent agents/replicas grab disjoint batches without blocking each
   * other.
   */
  public List<LeasedJob> lease(long dbId, int max) {
    return jdbc.query(
        "UPDATE validation_jobs SET state = 'LEASED', leased_at = now() "
            + "WHERE id IN ("
            + "  SELECT id FROM validation_jobs "
            + "  WHERE db_id = ? AND state = 'PENDING' "
            + "  ORDER BY created_at "
            + "  LIMIT ? "
            + "  FOR UPDATE SKIP LOCKED"
            + ") "
            + "RETURNING id, queryid, normalized_sql, candidate_ddl, access_method",
        (rs, n) ->
            new LeasedJob(
                rs.getLong("id"),
                rs.getLong("queryid"),
                rs.getString("normalized_sql"),
                rs.getString("candidate_ddl"),
                rs.getString("access_method")),
        dbId,
        max);
  }

  /**
   * Records one reported verdict: upserts the recommendation (with its HypoPG evidence + a ranking
   * score) and marks the job DONE. Constrained to {@code dbId} — an agent can only report jobs for
   * its own db — and to a non-terminal ({@code PENDING}/{@code LEASED}) job, so a stale report for
   * an already-{@code DONE} or dead-lettered ({@code FAILED}) job is ignored rather than
   * resurrected (ADR-0035, G6). A job reclaimed back to {@code PENDING} still accepts its late
   * result. Returns false (and does nothing) if the job is unknown, not this db's, or already
   * terminal. Call inside a transaction so the rec and the job state commit together.
   */
  public boolean recordResult(long dbId, ValidateResult r) {
    Optional<JobRef> job = findJob(dbId, r.getJobId());
    if (job.isEmpty()) {
      return false;
    }
    String status = r.getStatus().name();
    Double beforeCost = r.hasBeforeCost() ? r.getBeforeCost() : null;
    Double afterCost = r.hasAfterCost() ? r.getAfterCost() : null;
    Double relativeDrop = r.hasRelativeDrop() ? r.getRelativeDrop() : null;

    // Ranking score (ADR-0017): the query's real total exec time × the estimated relative drop —
    // only for a validated rec, only when we have both numbers. Always a labeled estimate.
    Double estimatedMsSaved = null;
    if (r.getStatus() == ValidationStatus.PLANNER_VALIDATED && relativeDrop != null) {
      Double totalExecTimeMs = queryTotalExecTimeMs(dbId, job.get().queryid());
      if (totalExecTimeMs != null) {
        estimatedMsSaved = relativeDrop * totalExecTimeMs;
      }
    }

    jdbc.update(
        "INSERT INTO recommendations (db_id, queryid, ddl, access_method, status, before_cost, "
            + "after_cost, relative_drop, used, reason, estimated_ms_saved, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) "
            + "ON CONFLICT (db_id, queryid, ddl) DO UPDATE SET "
            + "  status = excluded.status, before_cost = excluded.before_cost, "
            + "  after_cost = excluded.after_cost, relative_drop = excluded.relative_drop, "
            + "  used = excluded.used, reason = excluded.reason, "
            + "  estimated_ms_saved = excluded.estimated_ms_saved, updated_at = now()",
        dbId,
        job.get().queryid(),
        job.get().candidateDdl(),
        job.get().accessMethod(),
        status,
        beforeCost,
        afterCost,
        relativeDrop,
        r.getUsed(),
        r.getReason(),
        estimatedMsSaved);

    jdbc.update(
        "UPDATE validation_jobs SET state = 'DONE', result_status = ?, before_cost = ?, "
            + "after_cost = ?, relative_drop = ?, used = ?, reason = ?, completed_at = now() "
            + "WHERE id = ?",
        status,
        beforeCost,
        afterCost,
        relativeDrop,
        r.getUsed(),
        r.getReason(),
        r.getJobId());
    return true;
  }

  private record JobRef(long queryid, String candidateDdl, String accessMethod) {}

  private Optional<JobRef> findJob(long dbId, long jobId) {
    return jdbc
        .query(
            "SELECT queryid, candidate_ddl, access_method FROM validation_jobs "
                + "WHERE id = ? AND db_id = ? AND state IN ('PENDING', 'LEASED')",
            (rs, n) ->
                new JobRef(
                    rs.getLong("queryid"),
                    rs.getString("candidate_ddl"),
                    rs.getString("access_method")),
            jobId,
            dbId)
        .stream()
        .findFirst();
  }

  private Double queryTotalExecTimeMs(long dbId, long queryid) {
    List<Double> totals =
        jdbc.query(
            "SELECT total_exec_time_ms FROM query_cumulative WHERE db_id = ? AND queryid = ?",
            (rs, n) -> rs.getDouble("total_exec_time_ms"),
            dbId,
            queryid);
    return totals.isEmpty() ? null : totals.get(0);
  }
}
