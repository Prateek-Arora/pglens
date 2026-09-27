package com.pglens.server.impact;

import com.pglens.engine.rank.RankingScore;
import com.pglens.server.advice.Confirm;
import com.pglens.server.advice.RecommendationReadService;
import com.pglens.server.advice.RecommendationReadService.IndexRecommendation;
import com.pglens.server.databases.DatabaseService;
import com.pglens.server.databases.DatabaseService.AgentStatus;
import com.pglens.server.databases.DatabaseService.Database;
import com.pglens.server.impact.AppliedReadService.AppliedIndex;
import com.pglens.server.persistence.MonitoredDb;
import com.pglens.server.persistence.QueryPreviews;
import com.pglens.server.trend.TrendMath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The dashboard's first screen (ADR-0052): where the measured time went, how much of it sits in
 * queries PgLens has a planner-validated index for, the planner's estimate of what those indexes
 * would save, the best indexes to build, and the ones already built with their measured result.
 * Windows sum the hourly rollup (ADR-0045). Estimates use the engine's ranking drop ({@link
 * RankingScore}) against the window's measured time, per query at its best index — so one query's
 * time is never counted twice.
 */
@Service
public class OverviewService {

  /** One database's window. */
  public record DatabaseSummary(
      String name,
      AgentStatus agent,
      @Nullable Instant lastSampleAt,
      long queries,
      long calls,
      double measuredTotalMs,
      double measuredMsWithAdvice,
      double estimatedMsSaved,
      int indexesToConsider,
      int appliedIndexes) {}

  /**
   * One UTC hour across every database. All null when no database sent data that hour (a gap, never
   * a zero).
   */
  public record TimelinePoint(
      Instant hour,
      @Nullable Double measuredTotalMs,
      @Nullable Double measuredMsWithAdvice,
      @Nullable Double estimatedMsSaved) {}

  /**
   * {@code measuredMsWithAdvice}: the measured time of queries with at least one planner-validated
   * index to consider. {@code estimatedMsSaved}: for each such query, its measured time × the
   * planner's cost drop for its best index — an estimate, never a measured saving.
   */
  public record Overview(
      String window,
      Instant from,
      Instant to,
      long queries,
      long calls,
      double measuredTotalMs,
      double measuredMsWithAdvice,
      double estimatedMsSaved,
      int indexesToConsider,
      int notPlannerValidated,
      List<DatabaseSummary> databases,
      List<IndexRecommendation> topRecommendations,
      List<AppliedIndex> applied,
      String appliedCaveat,
      List<TimelinePoint> timeline,
      Confirm confirm) {}

  /** A query drawn as its own band in a database's timeline. */
  public record Series(
      String queryid,
      @Nullable String sqlPreview,
      double measuredTotalMs,
      @Nullable Double estimatedMsSaved) {}

  /**
   * One UTC hour of a database's timeline: its measured total, the part in queries with an index to
   * consider, the planner-estimated saving, and each series' measured time (aligned with {@code
   * series}). {@code sampled} is false when the agent didn't report that hour (the numbers are then
   * 0 and mean "no data", not "no load"); a reported hour with no query rows is a real zero.
   */
  public record DbTimelinePoint(
      Instant hour,
      boolean sampled,
      double measuredTotalMs,
      double measuredMsWithAdvice,
      double estimatedMsSaved,
      List<Double> measuredMsBySeries) {}

  public record DbTimeline(
      String database,
      String window,
      Instant from,
      Instant to,
      List<Series> series,
      List<DbTimelinePoint> points) {}

  private static final int TOP_RECOMMENDATIONS = 5;
  private static final int RECENT_APPLIED = 5;

  private final JdbcTemplate jdbc;
  private final DatabaseService databases;
  private final RecommendationReadService recommendations;
  private final AppliedReadService applied;
  private final QueryPreviews previews;
  private final Clock clock;

  public OverviewService(
      JdbcTemplate jdbc,
      DatabaseService databases,
      RecommendationReadService recommendations,
      AppliedReadService applied,
      QueryPreviews previews,
      Clock clock) {
    this.jdbc = jdbc;
    this.databases = databases;
    this.recommendations = recommendations;
    this.applied = applied;
    this.previews = previews;
    this.clock = clock;
  }

  public Overview overview(String window, Duration length) {
    Instant to = clock.instant();
    Instant from = TrendMath.windowStart(to, length);
    Map<String, Database> views =
        databases.list().stream().collect(Collectors.toMap(Database::name, Function.identity()));
    List<MonitoredDb> dbs = databases.all();

    List<DatabaseSummary> summaries = new ArrayList<>();
    Map<Instant, double[]> hours = new HashMap<>();
    Set<Instant> sampled = new HashSet<>();
    int notValidated = 0;
    for (MonitoredDb db : dbs) {
      Map<Long, Double> drops = bestDrops(db.id());
      List<QueryTotal> totals = queryTotals(db.id(), from);
      var recs = recommendations.forDatabase(db);
      notValidated += recs.notPlannerValidated().size();
      Database view = views.get(db.name());
      summaries.add(
          new DatabaseSummary(
              db.name(),
              view == null ? AgentStatus.NEVER_CONNECTED : view.agent(),
              view == null ? null : view.lastIngestAt(),
              totals.size(),
              totals.stream().mapToLong(QueryTotal::calls).sum(),
              totals.stream().mapToDouble(QueryTotal::totalMs).sum(),
              totals.stream()
                  .filter(t -> drops.containsKey(t.queryid()))
                  .mapToDouble(QueryTotal::totalMs)
                  .sum(),
              totals.stream()
                  .mapToDouble(t -> drops.getOrDefault(t.queryid(), 0.0) * t.totalMs())
                  .sum(),
              (int) recs.recommended().stream().filter(IndexRecommendation::actionable).count(),
              applied.forDatabase(db).items().size()));
      sampled.addAll(sampledHours(db.id(), from));
      hourly(db.id(), from, drops)
          .forEach(
              (hour, v) -> {
                double[] sum = hours.computeIfAbsent(hour, h -> new double[3]);
                for (int i = 0; i < 3; i++) {
                  sum[i] += v[i];
                }
              });
    }

    List<TimelinePoint> timeline = new ArrayList<>();
    for (Instant h = from; !h.isAfter(to); h = h.plus(1, ChronoUnit.HOURS)) {
      double[] v = hours.get(h);
      if (v == null && sampled.contains(h)) {
        v = new double[3]; // the agent reported that hour and no query ran: zero, not a gap
      }
      timeline.add(
          v == null
              ? new TimelinePoint(h, null, null, null)
              : new TimelinePoint(h, v[0], v[1], v[2]));
    }
    return new Overview(
        window,
        from,
        to,
        summaries.stream().mapToLong(DatabaseSummary::queries).sum(),
        summaries.stream().mapToLong(DatabaseSummary::calls).sum(),
        summaries.stream().mapToDouble(DatabaseSummary::measuredTotalMs).sum(),
        summaries.stream().mapToDouble(DatabaseSummary::measuredMsWithAdvice).sum(),
        summaries.stream().mapToDouble(DatabaseSummary::estimatedMsSaved).sum(),
        summaries.stream().mapToInt(DatabaseSummary::indexesToConsider).sum(),
        notValidated,
        summaries,
        recommendations.acrossDatabases(dbs, TOP_RECOMMENDATIONS).recommended(),
        applied.recent(dbs, RECENT_APPLIED),
        AppliedReadService.CAVEAT,
        timeline,
        Confirm.INSTANCE);
  }

  /** A database's measured time per UTC hour, split into its top queries and the rest. */
  public DbTimeline timeline(MonitoredDb db, String window, Duration length, int seriesCount) {
    Instant to = clock.instant();
    Instant from = TrendMath.windowStart(to, length);
    Map<Long, Double> drops = bestDrops(db.id());
    List<QueryTotal> top =
        queryTotals(db.id(), from).stream()
            .sorted(Comparator.comparingDouble(QueryTotal::totalMs).reversed())
            .limit(seriesCount)
            .toList();
    Map<Long, String> sql = previews.of(db.id(), top.stream().map(QueryTotal::queryid).toList());
    List<Series> series =
        top.stream()
            .map(
                t ->
                    new Series(
                        Long.toString(t.queryid()),
                        sql.get(t.queryid()),
                        t.totalMs(),
                        drops.containsKey(t.queryid())
                            ? drops.get(t.queryid()) * t.totalMs()
                            : null))
            .toList();

    Map<Long, Integer> slot = new HashMap<>();
    for (int i = 0; i < top.size(); i++) {
      slot.put(top.get(i).queryid(), i);
    }
    Map<Instant, double[]> totals = hourly(db.id(), from, drops);
    Set<Instant> sampled = sampledHours(db.id(), from);
    Map<Instant, double[]> bySeries = new HashMap<>();
    jdbc.query(
        "SELECT hour, queryid, total_exec_time_ms FROM query_stats_hourly "
            + "WHERE db_id = ? AND hour >= ? AND queryid = ANY (?)",
        rs -> {
          Integer i = slot.get(rs.getLong(2));
          if (i != null) {
            bySeries
                    .computeIfAbsent(rs.getTimestamp(1).toInstant(), h -> new double[top.size()])[
                    i] +=
                rs.getDouble(3);
          }
        },
        db.id(),
        utc(from),
        top.stream().map(QueryTotal::queryid).toArray(Long[]::new));

    List<DbTimelinePoint> points = new ArrayList<>();
    for (Instant h = from; !h.isAfter(to); h = h.plus(1, ChronoUnit.HOURS)) {
      double[] t = totals.get(h);
      if (t == null && sampled.contains(h)) {
        t = new double[3]; // the agent reported that hour and no query ran: zero, not a gap
      }
      double[] v = bySeries.getOrDefault(h, new double[top.size()]);
      List<Double> values = new ArrayList<>();
      for (double x : v) {
        values.add(x);
      }
      points.add(
          t == null
              ? new DbTimelinePoint(h, false, 0.0, 0.0, 0.0, values)
              : new DbTimelinePoint(h, true, t[0], t[1], t[2], values));
    }
    return new DbTimeline(db.name(), window, from, to, series, points);
  }

  private record QueryTotal(long queryid, long calls, double totalMs) {}

  private List<QueryTotal> queryTotals(long dbId, Instant from) {
    return jdbc.query(
        "SELECT queryid, sum(calls), sum(total_exec_time_ms) FROM query_stats_hourly "
            + "WHERE db_id = ? AND hour >= ? GROUP BY queryid",
        (rs, n) -> new QueryTotal(rs.getLong(1), rs.getLong(2), rs.getDouble(3)),
        dbId,
        utc(from));
  }

  /**
   * Each query's ranking drop at its best active planner-validated index (the engine's {@link
   * RankingScore#drop}); queries with none are absent.
   */
  Map<Long, Double> bestDrops(long dbId) {
    Map<Long, Double> out = new HashMap<>();
    jdbc.query(
        "SELECT queryid, max(relative_drop) FROM recommendations "
            + "WHERE db_id = ? AND status = 'PLANNER_VALIDATED' AND applied_at IS NULL "
            + "GROUP BY queryid",
        rs -> {
          out.put(rs.getLong(1), RankingScore.drop((Double) rs.getObject(2)).value());
        },
        dbId);
    return out;
  }

  /**
   * The UTC hours in which the agent reported at all ({@code agent_hours}, V13). {@code
   * query_stats} has no row for a query that didn't run, so an hour without rows is zero load when
   * the agent reported, and a gap only when it didn't.
   */
  private Set<Instant> sampledHours(long dbId, Instant from) {
    Set<Instant> out = new HashSet<>();
    jdbc.query(
        "SELECT hour FROM agent_hours WHERE db_id = ? AND hour >= ?",
        rs -> {
          out.add(rs.getTimestamp(1).toInstant());
        },
        dbId,
        utc(from));
    return out;
  }

  /** Per UTC hour: [measured total, measured with advice, estimated saved]. */
  private Map<Instant, double[]> hourly(long dbId, Instant from, Map<Long, Double> drops) {
    Map<Instant, double[]> out = new HashMap<>();
    List<Long> ids = new ArrayList<>(drops.keySet());
    jdbc.query(
        """
        SELECT h.hour,
               sum(h.total_exec_time_ms),
               coalesce(sum(h.total_exec_time_ms) FILTER (WHERE d.drop IS NOT NULL), 0),
               coalesce(sum(h.total_exec_time_ms * d.drop), 0)
        FROM query_stats_hourly h
        LEFT JOIN unnest(?::bigint[], ?::float8[]) AS d (queryid, drop) ON d.queryid = h.queryid
        WHERE h.db_id = ? AND h.hour >= ?
        GROUP BY h.hour
        """,
        rs -> {
          out.put(
              rs.getTimestamp(1).toInstant(),
              new double[] {rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)});
        },
        ids.toArray(Long[]::new),
        ids.stream().map(drops::get).toArray(Double[]::new),
        dbId,
        utc(from));
    return out;
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
