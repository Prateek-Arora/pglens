package com.pglens.server.persistence;

import com.pglens.server.trend.TrendPoint;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the {@code query_stats} delta time-series for the trend / top-mover / new-slow-query
 * questions (Phase 2, Step 7). Nothing here fabricates: every value is a persisted, measured delta,
 * and derived numbers (mean, percent change) are computed by the pure {@code TrendMath} in the
 * service layer.
 *
 * <p><b>Dogfood note.</b> {@link #windowTotals} and {@link #newQueries} scan {@code query_stats} by
 * {@code captured_at} range across <em>all</em> queryids — the cross-query access path the {@code
 * query_stats} primary key {@code (db_id, queryid, captured_at)} does <em>not</em> serve. V1
 * deliberately withheld the time-series index so the Step 11 dogfood benchmark could measure this;
 * it is now served by {@code BRIN(captured_at)} (migration {@code V4}, ADR-0033 — see {@code
 * docs/benchmarks.md} for the measured ~73× buffer reduction). The per-query {@link #series} scan
 * rides the PK.
 */
@Repository
public class TrendRepository {

  private final JdbcTemplate jdbc;

  public TrendRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** One query's interval samples over {@code [from, to)}, oldest first. Served by the PK. */
  public List<TrendPoint> series(long dbId, long queryid, Instant from, Instant to) {
    return jdbc.query(
        """
        SELECT captured_at, mean_exec_time_ms, calls_delta, total_exec_time_delta_ms
        FROM query_stats
        WHERE db_id = ? AND queryid = ? AND captured_at >= ? AND captured_at < ?
        ORDER BY captured_at
        """,
        (rs, n) ->
            new TrendPoint(
                rs.getTimestamp("captured_at").toInstant(),
                rs.getObject("mean_exec_time_ms", Double.class),
                rs.getLong("calls_delta"),
                rs.getDouble("total_exec_time_delta_ms")),
        dbId,
        queryid,
        utc(from),
        utc(to));
  }

  /** The normalized text registered for a query, if any (a PK lookup on {@code query_texts}). */
  public Optional<String> normalizedText(long dbId, long queryid) {
    return jdbc
        .query(
            "SELECT normalized_text FROM query_texts WHERE db_id = ? AND queryid = ?",
            (rs, n) -> rs.getString("normalized_text"),
            dbId,
            queryid)
        .stream()
        .findFirst();
  }

  /** Per-query summed time + calls in the recent vs. prior window (the top-movers input). */
  public record QueryWindowTotals(
      long queryid,
      String normalizedText,
      double recentTotalMs,
      long recentCalls,
      double priorTotalMs,
      long priorCalls) {}

  /**
   * Sums each query's {@code total_exec_time_delta} and {@code calls_delta} over two adjacent
   * windows in one scan: the recent window {@code [recentFrom, recentTo)} and the prior window
   * {@code [priorFrom, recentFrom)}. Only queries with at least one row in the combined span are
   * returned. Cross-query {@code captured_at}-range scan — served by the {@code V4} BRIN index.
   */
  public List<QueryWindowTotals> windowTotals(
      long dbId, Instant priorFrom, Instant recentFrom, Instant recentTo) {
    OffsetDateTime recent = utc(recentFrom);
    return jdbc.query(
        """
        SELECT s.queryid,
               t.normalized_text,
               COALESCE(sum(s.total_exec_time_delta_ms) FILTER (WHERE s.captured_at >= ?), 0)
                   AS recent_total,
               COALESCE(sum(s.calls_delta)              FILTER (WHERE s.captured_at >= ?), 0)
                   AS recent_calls,
               COALESCE(sum(s.total_exec_time_delta_ms) FILTER (WHERE s.captured_at <  ?), 0)
                   AS prior_total,
               COALESCE(sum(s.calls_delta)              FILTER (WHERE s.captured_at <  ?), 0)
                   AS prior_calls
        FROM query_stats s
        JOIN query_texts t ON t.db_id = s.db_id AND t.queryid = s.queryid
        WHERE s.db_id = ? AND s.captured_at >= ? AND s.captured_at < ?
        GROUP BY s.queryid, t.normalized_text
        """,
        (rs, n) ->
            new QueryWindowTotals(
                rs.getLong("queryid"),
                rs.getString("normalized_text"),
                rs.getDouble("recent_total"),
                rs.getLong("recent_calls"),
                rs.getDouble("prior_total"),
                rs.getLong("prior_calls")),
        recent,
        recent,
        recent,
        recent,
        dbId,
        utc(priorFrom),
        utc(recentTo));
  }

  /** A query whose first-ever sample falls in the recent window, with its window totals. */
  public record NewQueryTotals(
      long queryid,
      String normalizedText,
      Instant firstSeen,
      double recentTotalMs,
      long recentCalls) {}

  /**
   * Queries whose <em>earliest</em> {@code query_stats} row is inside {@code [recentFrom,
   * recentTo)} — i.e. never seen before the window — and whose summed time in the window is at
   * least {@code minTotalMs}. Ordered by recent total time, biggest first. The outer cross-query
   * {@code captured_at}-range scan is served by the {@code V4} BRIN index; the per-query "seen
   * before?" anti-join is served by the PK (and dominates this query's cost — see
   * docs/benchmarks.md).
   */
  public List<NewQueryTotals> newQueries(
      long dbId, Instant recentFrom, Instant recentTo, double minTotalMs) {
    return jdbc.query(
        """
        SELECT s.queryid,
               t.normalized_text,
               min(s.captured_at)              AS first_seen,
               sum(s.total_exec_time_delta_ms) AS recent_total,
               sum(s.calls_delta)              AS recent_calls
        FROM query_stats s
        JOIN query_texts t ON t.db_id = s.db_id AND t.queryid = s.queryid
        WHERE s.db_id = ? AND s.captured_at >= ? AND s.captured_at < ?
          AND NOT EXISTS (
            SELECT 1 FROM query_stats p
            WHERE p.db_id = s.db_id AND p.queryid = s.queryid AND p.captured_at < ?
          )
        GROUP BY s.queryid, t.normalized_text
        HAVING sum(s.total_exec_time_delta_ms) >= ?
        ORDER BY recent_total DESC
        """,
        (rs, n) ->
            new NewQueryTotals(
                rs.getLong("queryid"),
                rs.getString("normalized_text"),
                rs.getTimestamp("first_seen").toInstant(),
                rs.getDouble("recent_total"),
                rs.getLong("recent_calls")),
        dbId,
        utc(recentFrom),
        utc(recentTo),
        utc(recentFrom),
        minTotalMs);
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
