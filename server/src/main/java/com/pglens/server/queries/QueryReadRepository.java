package com.pglens.server.queries;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The read API's queries over the persisted history (ADR-0044): the windowed leaderboard, one
 * query's stored text/plan/totals, and its recommendation rows. Read-only; every number is a sum of
 * measured {@code pg_stat_statements} deltas or a stored, labeled planner estimate. Windowed sums
 * read the hourly rollup {@code query_stats_hourly} (V10, ADR-0045), so a window starts on a UTC
 * hour.
 */
@Repository
class QueryReadRepository {

  /** Leaderboard orderings. The SQL fragments are fixed here, never built from user input. */
  enum Sort {
    TOTAL("total_ms"),
    MEAN("mean_ms"),
    CALLS("calls");

    final String column;

    Sort(String column) {
      this.column = column;
    }
  }

  /** Characters of SQL shown per leaderboard row; the detail endpoint has the full text. */
  static final int PREVIEW_CHARS = 300;

  record Row(
      long queryid,
      long calls,
      double totalMs,
      Double meanMs,
      long rows,
      long sharedBlksHit,
      long sharedBlksRead,
      String preview,
      boolean previewCut,
      boolean planCaptured,
      String recommendation) {}

  record Page(List<Row> rows, long matched) {}

  record StoredQuery(
      String text,
      boolean truncated,
      String planJson,
      boolean planCaptured,
      Instant firstSeen,
      Instant lastSeen) {}

  record Totals(long calls, double totalMs, long rows, Instant asOf) {}

  record StoredRecommendation(
      String ddl,
      String accessMethod,
      String status,
      Double costBefore,
      Double costAfter,
      Double relativeDrop,
      Boolean used,
      String reason,
      Double estimatedMsSaved,
      String scoreBasis,
      String rangeLabel,
      String footprintLabel,
      String buildCaution,
      Instant validatedAt) {}

  private final JdbcTemplate jdbc;

  QueryReadRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * One page of the queries that ran in the hours {@code [from, to)} ({@code from} on a UTC hour),
   * by summed deltas. Aggregates, sorts and cuts the page first, and only then joins the page's
   * texts and recommendation verdicts.
   */
  Page leaderboard(long dbId, Instant from, Instant to, Sort sort, int limit, int offset) {
    String order = sort.column + " DESC NULLS LAST, queryid";
    long[] matched = {0}; // count(*) OVER () rides along on every row of the page
    List<Row> rows =
        jdbc.query(
            """
            WITH w AS (
                SELECT queryid,
                       sum(calls)                                       AS calls,
                       sum(total_exec_time_ms)                          AS total_ms,
                       sum(total_exec_time_ms) / NULLIF(sum(calls), 0)  AS mean_ms,
                       sum(rows)                                        AS rows,
                       sum(shared_blks_hit)                             AS blks_hit,
                       sum(shared_blks_read)                            AS blks_read
                FROM query_stats_hourly
                WHERE db_id = ? AND hour >= ? AND hour < ?
                GROUP BY queryid),
            page AS (
                SELECT w.*, count(*) OVER () AS matched FROM w
                ORDER BY %s LIMIT ? OFFSET ?)
            SELECT page.*,
                   left(t.normalized_text, %d)                                   AS preview,
                   (length(t.normalized_text) > %d OR t.truncated)               AS preview_cut,
                   COALESCE(t.plan_captured, false)                               AS plan_captured,
                   (SELECT CASE
                               WHEN bool_or(r.status = 'PLANNER_VALIDATED')
                                   THEN 'PLANNER_VALIDATED'
                               WHEN bool_or(r.status = 'NOT_PLANNER_VALIDATED')
                                   THEN 'NOT_PLANNER_VALIDATED'
                           END
                    FROM recommendations r
                    WHERE r.db_id = ? AND r.queryid = page.queryid)               AS rec_status
            FROM page
            LEFT JOIN query_texts t ON t.db_id = ? AND t.queryid = page.queryid
            ORDER BY %s
            """
                .formatted(order, PREVIEW_CHARS, PREVIEW_CHARS, order),
            (rs, n) -> {
              matched[0] = rs.getLong("matched");
              return new Row(
                  rs.getLong("queryid"),
                  rs.getLong("calls"),
                  rs.getDouble("total_ms"),
                  (Double) rs.getObject("mean_ms"),
                  rs.getLong("rows"),
                  rs.getLong("blks_hit"),
                  rs.getLong("blks_read"),
                  rs.getString("preview"),
                  Boolean.TRUE.equals(rs.getObject("preview_cut")),
                  rs.getBoolean("plan_captured"),
                  rs.getString("rec_status"));
            },
            dbId,
            utc(from),
            utc(to),
            limit,
            offset,
            dbId,
            dbId);
    if (rows.isEmpty() && offset > 0) {
      // Past the last page: count separately rather than report 0 matches.
      matched[0] =
          jdbc.queryForObject(
              "SELECT count(DISTINCT queryid) FROM query_stats_hourly "
                  + "WHERE db_id = ? AND hour >= ? AND hour < ?",
              Long.class,
              dbId,
              utc(from),
              utc(to));
    }
    return new Page(rows, matched[0]);
  }

  Optional<StoredQuery> query(long dbId, long queryid) {
    return jdbc
        .query(
            "SELECT normalized_text, truncated, plan_json, plan_captured, first_seen, last_seen "
                + "FROM query_texts WHERE db_id = ? AND queryid = ?",
            (rs, n) ->
                new StoredQuery(
                    rs.getString(1),
                    rs.getBoolean(2),
                    rs.getString(3),
                    rs.getBoolean(4),
                    instant(rs, 5),
                    instant(rs, 6)),
            dbId,
            queryid)
        .stream()
        .findFirst();
  }

  /**
   * Summed deltas for one query in the hours {@code [from, to)} — the leaderboard's numbers for it;
   * zero calls when it didn't run.
   */
  Totals window(long dbId, long queryid, Instant from, Instant to) {
    return jdbc.queryForObject(
        "SELECT COALESCE(sum(calls), 0), COALESCE(sum(total_exec_time_ms), 0), "
            + "COALESCE(sum(rows), 0) FROM query_stats_hourly "
            + "WHERE db_id = ? AND queryid = ? AND hour >= ? AND hour < ?",
        (rs, n) -> new Totals(rs.getLong(1), rs.getDouble(2), rs.getLong(3), to),
        dbId,
        queryid,
        utc(from),
        utc(to));
  }

  /** The latest cumulative counters the agent sent (since the last stats reset). */
  Optional<Totals> cumulative(long dbId, long queryid) {
    return jdbc
        .query(
            "SELECT calls, total_exec_time_ms, rows, captured_at FROM query_cumulative "
                + "WHERE db_id = ? AND queryid = ?",
            (rs, n) -> new Totals(rs.getLong(1), rs.getDouble(2), rs.getLong(3), instant(rs, 4)),
            dbId,
            queryid)
        .stream()
        .findFirst();
  }

  List<StoredRecommendation> recommendations(long dbId, long queryid) {
    return jdbc.query(
        "SELECT ddl, access_method, status, before_cost, after_cost, relative_drop, used, reason, "
            + "estimated_ms_saved, score_basis, range_label, footprint_label, build_caution, "
            + "updated_at FROM recommendations WHERE db_id = ? AND queryid = ? "
            + "ORDER BY estimated_ms_saved DESC NULLS LAST, ddl",
        (rs, n) ->
            new StoredRecommendation(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                (Double) rs.getObject(4),
                (Double) rs.getObject(5),
                (Double) rs.getObject(6),
                (Boolean) rs.getObject(7),
                rs.getString(8),
                (Double) rs.getObject(9),
                rs.getString(10),
                rs.getString(11),
                rs.getString(12),
                rs.getString(13),
                instant(rs, 14)),
        dbId,
        queryid);
  }

  private static Instant instant(ResultSet rs, int column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private static OffsetDateTime utc(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }
}
