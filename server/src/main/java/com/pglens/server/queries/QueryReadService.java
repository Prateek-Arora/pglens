package com.pglens.server.queries;

import com.pglens.engine.detect.AntiPatternDetector;
import com.pglens.engine.model.Finding;
import com.pglens.engine.model.PlanNode;
import com.pglens.engine.parse.PlanParser;
import com.pglens.server.advice.Confirm;
import com.pglens.server.errors.Errors.NotFound;
import com.pglens.server.persistence.CatalogRepository;
import com.pglens.server.queries.QueryReadRepository.Row;
import com.pglens.server.queries.QueryReadRepository.Sort;
import com.pglens.server.queries.QueryReadRepository.StoredQuery;
import com.pglens.server.queries.QueryReadRepository.StoredRecommendation;
import com.pglens.server.queries.QueryReadRepository.Totals;
import com.pglens.server.trend.TrendMath;
import com.pglens.server.trend.TrendPoint;
import com.pglens.server.trend.TrendService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The read API's per-query views (ADR-0044): the windowed leaderboard, one query in detail (SQL,
 * estimated plan tree, findings pointing at plan nodes, recommendations, measured totals) and its
 * trend. {@code queryid} is a string in every view: it is a signed 64-bit value, and JavaScript
 * numbers are exact only to 2⁵³. Field names say what a number is — {@code measured…} sums real
 * {@code pg_stat_statements} deltas; {@code estimated…} and {@code plannerCost…} are planner
 * estimates.
 */
@Service
public class QueryReadService {

  private static final Logger log = LoggerFactory.getLogger(QueryReadService.class);

  static final String PLAN_LABEL =
      "Planner estimates from EXPLAIN (GENERIC_PLAN): the query was not run, so rows and costs are"
          + " estimates (costs are in planner units, not milliseconds).";

  /**
   * One row of the leaderboard: a query's summed activity in the window. {@code recommendation} is
   * the best verdict among the indexes suggested for it — {@code PLANNER_VALIDATED}, {@code
   * NOT_PLANNER_VALIDATED} (GIN/GiST: surfaced, not checkable by HypoPG) — or null for none.
   */
  public record LeaderboardEntry(
      String queryid,
      String sqlPreview,
      boolean sqlPreviewCut,
      long calls,
      double measuredTotalMs,
      Double measuredMeanMs,
      long rows,
      long sharedBlksHit,
      long sharedBlksRead,
      boolean planCaptured,
      String recommendation) {}

  public record Leaderboard(
      String window,
      Instant from,
      Instant to,
      String sort,
      long total,
      int limit,
      int offset,
      List<LeaderboardEntry> items) {}

  /** Measured totals over a period ({@code measuredMeanMs} is null when there were no calls). */
  public record MeasuredTotals(
      long calls, double measuredTotalMs, Double measuredMeanMs, long rows, Instant asOf) {}

  /** One node of the estimated plan; {@code id} is its pre-order position (0 = root). */
  public record PlanNodeView(
      int id,
      String nodeType,
      boolean parallelAware,
      String relation,
      String alias,
      String index,
      String joinType,
      double estimatedStartupCost,
      double estimatedTotalCost,
      long estimatedRows,
      int estimatedWidth,
      String filter,
      String indexCond,
      String recheckCond,
      String hashCond,
      List<String> sortKeys,
      Integer workersPlanned,
      List<PlanNodeView> children) {}

  public record PlanView(
      String captureMode, String label, double estimatedTotalCost, PlanNodeView root) {}

  /** A plan pattern the detector flagged; {@code planNode} is the id of the node it is about. */
  public record FindingView(
      String ruleId,
      String title,
      String table,
      List<String> columns,
      String confidence,
      String evidence,
      Integer planNode) {}

  /**
   * One index checked for this query. {@code plannerCostDropFraction} is {@code (before − after) /
   * before} of the planner's estimated cost; {@code estimatedMsSaved} is the query's measured time
   * scaled by that drop — an estimate, used for ranking.
   */
  public record QueryRecommendation(
      String ddl,
      String accessMethod,
      String status,
      Double plannerCostBefore,
      Double plannerCostAfter,
      Double plannerCostDropFraction,
      Boolean usedByPlanner,
      String reason,
      Double estimatedMsSaved,
      String scoreBasis,
      String rangeLabel,
      String footprintLabel,
      String buildCaution,
      Instant validatedAt) {}

  public record QueryDetail(
      String database,
      String queryid,
      String sql,
      boolean sqlTruncated,
      Instant firstSeen,
      Instant lastSeen,
      String window,
      MeasuredTotals inWindow,
      MeasuredTotals sinceStatsReset,
      PlanView plan,
      List<FindingView> findings,
      List<QueryRecommendation> recommendations,
      Confirm confirm) {}

  /**
   * One persisted interval (or UTC hour); an interval with no row is a gap, never a zero. {@code
   * capturedAt} is the interval's receive time, or the hour's start.
   */
  public record TrendPointView(
      Instant capturedAt, long calls, double measuredTotalMs, Double measuredMeanMs) {}

  /** {@code resolution}: {@code RAW} (one point per persisted interval) or {@code HOUR}. */
  public record Trend(
      String queryid, Instant from, Instant to, String resolution, List<TrendPointView> points) {}

  private final QueryReadRepository repo;
  private final CatalogRepository catalogs;
  private final TrendService trends;
  private final Clock clock;
  private final PlanParser planParser = new PlanParser();
  private final AntiPatternDetector detector = new AntiPatternDetector();

  public QueryReadService(
      QueryReadRepository repo, CatalogRepository catalogs, TrendService trends, Clock clock) {
    this.repo = repo;
    this.catalogs = catalogs;
    this.trends = trends;
    this.clock = clock;
  }

  public Leaderboard leaderboard(
      long dbId, String window, Duration length, String sort, int limit, int offset) {
    Instant to = clock.instant();
    Instant from = TrendMath.windowStart(to, length);
    QueryReadRepository.Page page =
        repo.leaderboard(dbId, from, to, Sort.valueOf(sort.toUpperCase()), limit, offset);
    return new Leaderboard(
        window,
        from,
        to,
        sort,
        page.matched(),
        limit,
        offset,
        page.rows().stream().map(QueryReadService::entry).toList());
  }

  public QueryDetail detail(
      long dbId, String database, long queryid, String window, Duration length) {
    StoredQuery q = repo.query(dbId, queryid).orElseThrow(() -> notFound(database, queryid));
    Instant to = clock.instant();
    PlanNode root = parse(q, database, queryid);
    List<FindingView> findings =
        root == null
            ? List.of()
            : detector.detect(root, catalogs.load(dbId)).stream()
                .map(QueryReadService::finding)
                .toList();
    return new QueryDetail(
        database,
        Long.toString(queryid),
        q.text(),
        q.truncated(),
        q.firstSeen(),
        q.lastSeen(),
        window,
        totals(repo.window(dbId, queryid, TrendMath.windowStart(to, length), to)),
        repo.cumulative(dbId, queryid).map(QueryReadService::totals).orElse(null),
        root == null
            ? null
            : new PlanView("generic_plan", PLAN_LABEL, root.totalCost(), node(root, new int[1])),
        findings,
        repo.recommendations(dbId, queryid).stream().map(QueryReadService::recommendation).toList(),
        Confirm.INSTANCE);
  }

  /** Trend resolutions: every persisted interval, or one point per UTC hour (the rollup). */
  public enum Resolution {
    RAW,
    HOUR
  }

  /** Up to this span, {@code auto} returns raw intervals; beyond it, hourly points. */
  static final Duration AUTO_RAW_MAX = Duration.ofHours(48);

  /**
   * The query's trend in {@code [from, to)}. {@code resolution} null = auto: raw intervals for a
   * span up to 48 h, else hourly (a 30-day raw trend is ~8,600 points and ~1 MB, more than a chart
   * can use). Hourly points start on UTC hours.
   */
  public Trend trend(
      long dbId, String database, long queryid, Instant from, Instant to, Resolution resolution) {
    requireQuery(dbId, database, queryid);
    Resolution r =
        resolution != null
            ? resolution
            : Duration.between(from, to).compareTo(AUTO_RAW_MAX) <= 0
                ? Resolution.RAW
                : Resolution.HOUR;
    List<TrendPoint> series =
        r == Resolution.RAW
            ? trends.series(dbId, queryid, from, to).points()
            : trends.hourlySeries(dbId, queryid, from, to);
    List<TrendPointView> points = new ArrayList<>();
    for (TrendPoint p : series) {
      points.add(
          new TrendPointView(
              p.capturedAt(), p.callsDelta(), p.totalExecTimeDeltaMs(), p.meanExecTimeMs()));
    }
    return new Trend(Long.toString(queryid), from, to, r.name(), points);
  }

  /** Throws 404 unless this query has stored history in this database. */
  public void requireQuery(long dbId, String database, long queryid) {
    if (repo.query(dbId, queryid).isEmpty()) {
      throw notFound(database, queryid);
    }
  }

  private PlanNode parse(StoredQuery q, String database, long queryid) {
    if (!q.planCaptured() || q.planJson() == null) {
      return null;
    }
    try {
      return planParser.parse(q.planJson());
    } catch (RuntimeException e) {
      log.warn(
          "queryid {} on '{}': stored plan did not parse ({})", queryid, database, e.toString());
      return null;
    }
  }

  /** Pre-order numbering, the same order as {@link PlanNode#flatten()} and finding node ids. */
  private static PlanNodeView node(PlanNode n, int[] next) {
    int id = next[0]++;
    List<PlanNodeView> children = new ArrayList<>();
    for (PlanNode child : n.children()) {
      children.add(node(child, next));
    }
    return new PlanNodeView(
        id,
        n.nodeType(),
        n.parallelAware(),
        n.relationName(),
        n.alias(),
        n.indexName(),
        n.joinType(),
        n.startupCost(),
        n.totalCost(),
        n.planRows(),
        n.planWidth(),
        n.filter(),
        n.indexCond(),
        n.recheckCond(),
        n.hashCond(),
        n.sortKeys(),
        n.workersPlanned(),
        children);
  }

  private static LeaderboardEntry entry(Row r) {
    return new LeaderboardEntry(
        Long.toString(r.queryid()),
        r.preview(),
        r.previewCut(),
        r.calls(),
        r.totalMs(),
        r.meanMs(),
        r.rows(),
        r.sharedBlksHit(),
        r.sharedBlksRead(),
        r.planCaptured(),
        r.recommendation());
  }

  private static MeasuredTotals totals(Totals t) {
    return new MeasuredTotals(
        t.calls(), t.totalMs(), TrendMath.mean(t.totalMs(), t.calls()), t.rows(), t.asOf());
  }

  private static FindingView finding(Finding f) {
    return new FindingView(
        f.ruleId(),
        f.title(),
        f.table(),
        f.columns(),
        f.confidence().name(),
        f.evidence(),
        f.planNode());
  }

  private static QueryRecommendation recommendation(StoredRecommendation r) {
    return new QueryRecommendation(
        r.ddl(),
        r.accessMethod(),
        r.status(),
        r.costBefore(),
        r.costAfter(),
        r.relativeDrop(),
        r.used(),
        r.reason(),
        r.estimatedMsSaved(),
        r.scoreBasis(),
        r.rangeLabel(),
        r.footprintLabel(),
        r.buildCaution(),
        r.validatedAt());
  }

  private static NotFound notFound(String database, long queryid) {
    return new NotFound("No query " + queryid + " in database '" + database + "'.");
  }
}
