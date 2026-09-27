package com.pglens.server.impact;

import com.pglens.engine.model.IndexCandidate;
import com.pglens.server.persistence.MonitoredDb;
import com.pglens.server.persistence.QueryPreviews;
import com.pglens.server.persistence.RetentionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Recommendations that were applied — an index serving them appeared on the database after PgLens
 * recommended it (ADR-0051) — each with its queries' measured mean time before and after.
 */
@Service
public class AppliedReadService {

  /** What the before/after numbers are, carried on every response. */
  public static final String CAVEAT =
      "Measured by pg_stat_statements: each query's mean time per call in the 7 days before PgLens "
          + "first saw the index, against the mean since (up to 7 days), leaving out the sample it "
          + "appeared in (for indexes older than the kept samples, the hour). A before/after "
          + "comparison, not a controlled experiment: data, load and other changes move it too. "
          + "A change is shown once each side has at least 10 calls.";

  /**
   * One query the applied index was recommended for. {@code status}: {@code MEASURED}, {@code
   * MEASURING} (not enough calls since) or {@code NO_BASELINE} (not enough calls before). {@code
   * measuredChangeFraction} is {@code (after − before) / before} of the mean time per call.
   */
  public record AppliedQuery(
      String queryid,
      @Nullable String sqlPreview,
      String status,
      long callsBefore,
      @Nullable Double measuredMeanMsBefore,
      long callsAfter,
      @Nullable Double measuredMeanMsAfter,
      @Nullable Double measuredChangeFraction,
      @Nullable Double plannerCostDropFraction) {}

  /**
   * A recommended index that now exists: {@code index} is the existing index that serves it (any
   * name), {@code appliedAt} when PgLens first saw it.
   */
  public record AppliedIndex(
      String database,
      String ddl,
      @Nullable String table,
      String index,
      Instant appliedAt,
      List<AppliedQuery> queries) {}

  public record Applied(String database, String caveat, List<AppliedIndex> items) {}

  private final JdbcTemplate jdbc;
  private final QueryPreviews previews;
  private final Clock clock;
  private final Duration rawKept;

  public AppliedReadService(
      JdbcTemplate jdbc,
      QueryPreviews previews,
      Clock clock,
      @Value("${pglens.retention.raw-days:35}") int rawDays) {
    this.jdbc = jdbc;
    this.previews = previews;
    this.clock = clock;
    this.rawKept = RetentionService.kept(rawDays);
  }

  public Applied forDatabase(MonitoredDb db) {
    return new Applied(db.name(), CAVEAT, load(db, null));
  }

  /** The applied indexes that were recommended for one query, with only that query's numbers. */
  public List<AppliedIndex> forQuery(MonitoredDb db, long queryid) {
    return load(db, queryid);
  }

  /** The most recently applied indexes across databases. */
  public List<AppliedIndex> recent(List<MonitoredDb> dbs, int limit) {
    List<AppliedIndex> all = new ArrayList<>();
    dbs.forEach(db -> all.addAll(load(db, null)));
    all.sort(Comparator.comparing(AppliedIndex::appliedAt).reversed());
    return all.stream().limit(limit).toList();
  }

  private record Row(
      String ddl,
      String index,
      Instant appliedAt,
      @Nullable Instant activeSince,
      long queryid,
      @Nullable Double drop) {}

  private List<AppliedIndex> load(MonitoredDb db, @Nullable Long onlyQuery) {
    List<Object> args = new ArrayList<>(List.of(db.id()));
    if (onlyQuery != null) {
      args.add(onlyQuery);
    }
    List<Row> rows =
        jdbc.query(
            "SELECT ddl, applied_index, applied_at, active_since, queryid, relative_drop "
                + "FROM recommendations "
                + "WHERE db_id = ? AND applied_at IS NOT NULL "
                + (onlyQuery == null ? "" : "AND queryid = ? ")
                + "ORDER BY applied_at DESC, ddl, queryid",
            (rs, n) ->
                new Row(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getTimestamp(3).toInstant(),
                    rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(),
                    rs.getLong(5),
                    (Double) rs.getObject(6)),
            args.toArray());
    Map<Long, String> sql = previews.of(db.id(), rows.stream().map(Row::queryid).toList());
    Map<String, AppliedIndex> byDdl = new LinkedHashMap<>();
    for (Row r : rows) {
      ImpactMath.BeforeAfter m = measure(db.id(), r.queryid(), r.appliedAt(), r.activeSince());
      AppliedQuery q =
          new AppliedQuery(
              Long.toString(r.queryid()),
              sql.get(r.queryid()),
              m.status().name(),
              m.callsBefore(),
              m.meanBefore(),
              m.callsAfter(),
              m.meanAfter(),
              m.change(),
              r.drop());
      byDdl
          .computeIfAbsent(
              r.ddl(),
              ddl ->
                  new AppliedIndex(
                      db.name(),
                      ddl,
                      IndexCandidate.parseDdl(ddl).map(IndexCandidate::table).orElse(null),
                      r.index(),
                      r.appliedAt(),
                      new ArrayList<>()))
          .queries()
          .add(q);
    }
    // Each index's queries: the one it was most clearly recommended for (largest planner drop)
    // first.
    Comparator<AppliedQuery> byDrop =
        Comparator.comparing(
            AppliedQuery::plannerCostDropFraction, Comparator.nullsLast(Comparator.reverseOrder()));
    byDdl.values().forEach(a -> a.queries().sort(byDrop));
    return List.copyOf(byDdl.values());
  }

  /**
   * The query's summed activity on each side of {@code appliedAt}, over at most {@link
   * ImpactMath#WINDOW} each way. "Before" starts no earlier than {@code activeSince} (a rebuilt
   * index's earlier life never counts as before). While the per-sample rows are kept, the split is
   * exact: the sample captured at {@code appliedAt} — the batch whose catalog first listed the
   * index, whose interval the index appeared in — belongs to neither side. Past raw retention it
   * uses the hourly rollup, leaving out the whole hour instead (ADR-0051).
   */
  private ImpactMath.BeforeAfter measure(
      long dbId, long queryid, Instant appliedAt, @Nullable Instant activeSince) {
    Instant from = appliedAt.minus(ImpactMath.WINDOW);
    if (activeSince != null && activeSince.isAfter(from)) {
      from = activeSince;
    }
    Instant to = appliedAt.plus(ImpactMath.WINDOW);
    if (!from.isBefore(clock.instant().minus(rawKept))) {
      return sums(
          """
          SELECT coalesce(sum(calls_delta) FILTER (WHERE captured_at < ?), 0),
                 coalesce(sum(total_exec_time_delta_ms) FILTER (WHERE captured_at < ?), 0),
                 coalesce(sum(calls_delta) FILTER (WHERE captured_at > ?), 0),
                 coalesce(sum(total_exec_time_delta_ms) FILTER (WHERE captured_at > ?), 0)
          FROM query_stats
          WHERE db_id = ? AND queryid = ? AND captured_at >= ? AND captured_at <= ?
          """,
          utc(appliedAt),
          dbId,
          queryid,
          utc(from),
          utc(to));
    }
    // Hours that lie wholly on one side: from the first full hour to the last full hour.
    Instant firstHour = from.truncatedTo(ChronoUnit.HOURS);
    if (firstHour.isBefore(from)) {
      firstHour = firstHour.plus(1, ChronoUnit.HOURS);
    }
    return sums(
        """
        SELECT coalesce(sum(calls) FILTER (WHERE hour < ?), 0),
               coalesce(sum(total_exec_time_ms) FILTER (WHERE hour < ?), 0),
               coalesce(sum(calls) FILTER (WHERE hour > ?), 0),
               coalesce(sum(total_exec_time_ms) FILTER (WHERE hour > ?), 0)
        FROM query_stats_hourly
        WHERE db_id = ? AND queryid = ? AND hour >= ? AND hour <= ?
        """,
        utc(appliedAt.truncatedTo(ChronoUnit.HOURS)),
        dbId,
        queryid,
        utc(firstHour),
        utc(to.truncatedTo(ChronoUnit.HOURS)));
  }

  /** Runs a before/after query whose four split parameters are all {@code split}. */
  private ImpactMath.BeforeAfter sums(
      String sql,
      OffsetDateTime split,
      long dbId,
      long queryid,
      OffsetDateTime from,
      OffsetDateTime to) {
    return jdbc.queryForObject(
        sql,
        (rs, n) ->
            new ImpactMath.BeforeAfter(
                rs.getLong(1), rs.getDouble(2), rs.getLong(3), rs.getDouble(4)),
        split,
        split,
        split,
        split,
        dbId,
        queryid,
        from,
        to);
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
