package com.pglens.engine.db;

import com.pglens.engine.PgLensException;
import com.pglens.engine.model.RankBy;
import com.pglens.engine.model.StatementStat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Reads and ranks statements from {@code pg_stat_statements} on the target database.
 *
 * <p>Hygiene (ADR-0014): only top-level statements ({@code toplevel = true}, which drops the nested
 * per-row FK-check triggers that {@code pg_stat_statements.track=all} records), only the current
 * database, and no utility statements or PgLens's own introspection.
 */
public class StatsReader {

  // Shared projection: exactly the columns MAPPER reads, so the two queries can't drift from it.
  private static final String SELECT_FROM =
      """
      SELECT s.queryid,
             s.query,
             octet_length(s.query) >= pg_size_bytes(current_setting('track_activity_query_size')) AS truncated,
             s.calls,
             s.total_exec_time,
             s.mean_exec_time,
             s.rows,
             s.shared_blks_hit,
             s.shared_blks_read
      FROM pg_stat_statements s
      """;

  // Hygiene (ADR-0014): top-level statements in the current database only. queryid is unique only
  // within a (dbid, ...) so the dbid filter is a correctness requirement, not just noise reduction.
  private static final String HYGIENE_WHERE =
      """
      WHERE s.toplevel
        AND s.dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
      """;

  // The ranking column is chosen from a fixed enum mapping (orderColumn) — user input is NEVER
  // interpolated into SQL. The literal '%' in ILIKE patterns is doubled for String.formatted().
  private static final String TOP_SQL =
      SELECT_FROM
          + HYGIENE_WHERE
          + """
          AND s.calls >= ?
          AND s.query !~* '^\\s*(set|show|begin|commit|rollback|savepoint|release|vacuum|analyze|reset|discard|deallocate|prepare|execute|create|alter|drop|grant|revoke|truncate|copy|checkpoint|cluster|reindex|refresh|listen|notify|unlisten|comment|do|call|explain|lock)\\y'
          AND s.query NOT ILIKE '%%hypopg%%'
          AND s.query NOT ILIKE '%%pg_stat_statements%%'
          AND s.query NOT LIKE '%%pglens:introspection%%'
        ORDER BY %s DESC NULLS LAST, s.total_exec_time DESC
        LIMIT ?
        """;

  // Explicit drill-in by queryid: the caller asked for exactly this id, so no utility/self filter —
  // just top-level + current db. If track=all recorded several rows, take the costliest.
  private static final String BY_QUERYID_SQL =
      SELECT_FROM
          + HYGIENE_WHERE
          + """
          AND s.queryid = ?
        ORDER BY s.total_exec_time DESC
        LIMIT 1
        """;

  private static final RowMapper<StatementStat> MAPPER =
      (rs, rowNum) ->
          new StatementStat(
              rs.getLong("queryid"),
              rs.getString("query"),
              rs.getBoolean("truncated"),
              rs.getLong("calls"),
              rs.getDouble("total_exec_time"),
              rs.getDouble("mean_exec_time"),
              rs.getLong("rows"),
              rs.getLong("shared_blks_hit"),
              rs.getLong("shared_blks_read"));

  /**
   * Oldest supported server ({@code server_version_num}): PG16 added {@code EXPLAIN
   * (GENERIC_PLAN)}, which is how PgLens plans normalized {@code $N} text without inventing
   * parameter values.
   */
  static final int MIN_SERVER_VERSION_NUM = 160000;

  private final JdbcTemplate jdbc;

  public StatsReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Top {@code limit} statements ranked by {@code rankBy}, keeping only those called ≥ minCalls.
   */
  public List<StatementStat> topStatements(RankBy rankBy, int limit, long minCalls) {
    ensureSupportedTarget();
    String sql = TOP_SQL.formatted(orderColumn(rankBy));
    try {
      return jdbc.query(sql, MAPPER, minCalls, limit);
    } catch (DataAccessException e) {
      throw statsReadError(e);
    }
  }

  /** The single statement with this {@code queryid} in the current database, if present. */
  public Optional<StatementStat> findByQueryId(long queryId) {
    ensureSupportedTarget();
    try {
      return jdbc.query(BY_QUERYID_SQL, MAPPER, queryId).stream().findFirst();
    } catch (DataAccessException e) {
      throw statsReadError(e);
    }
  }

  /**
   * The time of the last <em>global</em> {@code pg_stat_statements_reset()} on the target — the
   * single {@code stats_reset} timestamp in {@code pg_stat_statements_info} — or empty if it has
   * never been reset. Present since pgss 1.9 (PG14); on PgLens's PG16 image (pgss 1.10) this is the
   * reset signal the agent forwards each interval so the server can detect a mid-stream reset and
   * anchor the delta to the post-reset cumulative (ADR-0024). Per-entry {@code stats_since} would
   * be finer-grained but is PG17-only (pgss 1.11), so it is deliberately not used here.
   */
  public Optional<Instant> globalStatsReset() {
    ensureSupportedTarget();
    try {
      OffsetDateTime ts =
          jdbc.queryForObject(
              DataSources.introspection("SELECT stats_reset FROM pg_stat_statements_info"),
              OffsetDateTime.class);
      return Optional.ofNullable(ts).map(OffsetDateTime::toInstant);
    } catch (DataAccessException e) {
      throw statsReadError(e);
    }
  }

  private static PgLensException statsReadError(DataAccessException e) {
    return new PgLensException(
        "Failed to read pg_stat_statements — the login role may need read access "
            + "(GRANT pg_read_all_stats TO <role>). Cause: "
            + e.getMostSpecificCause().getMessage(),
        e);
  }

  /**
   * Fails fast, with an actionable message, unless the target is PG16+ with {@code
   * pg_stat_statements} installed (one round trip). Before PG16 every plan capture would fail and
   * the report would be a list of uncaptured queries — refusing up front is the honest outcome
   * (ADR-0036).
   */
  private void ensureSupportedTarget() {
    final Target target;
    try {
      target =
          jdbc.queryForObject(
              DataSources.introspection(
                  "SELECT current_setting('server_version_num')::int AS version_num, "
                      + "current_setting('server_version') AS version, "
                      + "EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements') "
                      + "AS pgss"),
              (rs, rowNum) ->
                  new Target(
                      rs.getInt("version_num"), rs.getString("version"), rs.getBoolean("pgss")));
    } catch (DataAccessException e) {
      throw new PgLensException(
          "Cannot query the target database: " + e.getMostSpecificCause().getMessage(), e);
    }
    requireSupportedVersion(target.versionNum(), target.version());
    if (!target.pgss()) {
      throw new PgLensException(
          "pg_stat_statements is not enabled on the target database. Add it to "
              + "shared_preload_libraries, restart, then run: CREATE EXTENSION pg_stat_statements;");
    }
  }

  /** Pure version gate, split out so it is unit-testable without an old Postgres. */
  static void requireSupportedVersion(int versionNum, String version) {
    if (versionNum < MIN_SERVER_VERSION_NUM) {
      throw new PgLensException(
          "PgLens requires PostgreSQL 16 or newer; the target is PostgreSQL "
              + version
              + ". PgLens plans the normalized pg_stat_statements text with EXPLAIN (GENERIC_PLAN),"
              + " which was added in PostgreSQL 16.");
    }
  }

  private record Target(int versionNum, String version, boolean pgss) {}

  private static String orderColumn(RankBy rankBy) {
    return switch (rankBy) {
      case TOTAL_TIME -> "s.total_exec_time";
      case MEAN_TIME -> "s.mean_exec_time";
      case CALLS -> "s.calls";
    };
  }
}
