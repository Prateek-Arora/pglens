package com.pglens.engine.db;

import com.pglens.engine.PgLensException;
import com.pglens.engine.model.RankBy;
import com.pglens.engine.model.StatementStat;
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

  private final JdbcTemplate jdbc;

  public StatsReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Top {@code limit} statements ranked by {@code rankBy}, keeping only those called ≥ minCalls.
   */
  public List<StatementStat> topStatements(RankBy rankBy, int limit, long minCalls) {
    ensureExtensionPresent();
    String sql = TOP_SQL.formatted(orderColumn(rankBy));
    try {
      return jdbc.query(sql, MAPPER, minCalls, limit);
    } catch (DataAccessException e) {
      throw statsReadError(e);
    }
  }

  /** The single statement with this {@code queryid} in the current database, if present. */
  public Optional<StatementStat> findByQueryId(long queryId) {
    ensureExtensionPresent();
    try {
      return jdbc.query(BY_QUERYID_SQL, MAPPER, queryId).stream().findFirst();
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

  private void ensureExtensionPresent() {
    final Integer present;
    try {
      present =
          jdbc.queryForObject(
              DataSources.INTROSPECTION_MARKER
                  + "SELECT count(*) FROM pg_extension WHERE extname = 'pg_stat_statements'",
              Integer.class);
    } catch (DataAccessException e) {
      throw new PgLensException(
          "Cannot query the target database: " + e.getMostSpecificCause().getMessage(), e);
    }
    if (present == null || present == 0) {
      throw new PgLensException(
          "pg_stat_statements is not enabled on the target database. Add it to "
              + "shared_preload_libraries, restart, then run: CREATE EXTENSION pg_stat_statements;");
    }
  }

  private static String orderColumn(RankBy rankBy) {
    return switch (rankBy) {
      case TOTAL_TIME -> "s.total_exec_time";
      case MEAN_TIME -> "s.mean_exec_time";
      case CALLS -> "s.calls";
    };
  }
}
