package com.pglens.server.trend;

import com.pglens.server.persistence.TrendRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Answers the trend / leaderboard questions over the persisted {@code query_stats} time-series
 * (Phase 2, Step 7): a query's mean_exec_time over time, the top movers between two adjacent
 * windows, and newly-appeared slow queries. This is the service seam a Phase 4 REST/GraphQL layer
 * will call; only the shape the Step 7 integration test drives is built now (YAGNI).
 *
 * <p>The service owns the <em>window policy</em> — "recent" vs. the equal-length window before it
 * (week-over-week by default) — and delegates every measured value to {@link TrendRepository}. All
 * derived numbers go through the pure {@link TrendMath}, so no fabricated ratio can slip in.
 */
@Service
public class TrendService {

  private final TrendRepository repo;

  public TrendService(TrendRepository repo) {
    this.repo = repo;
  }

  /** A single query's interval series over {@code [from, to)}, plus its normalized text. */
  public QueryTrend series(long dbId, long queryid, Instant from, Instant to) {
    List<TrendPoint> points = repo.series(dbId, queryid, from, to);
    String text = repo.normalizedText(dbId, queryid).orElse(null);
    return new QueryTrend(queryid, text, points);
  }

  /**
   * Top movers by total-time change over the last {@code window}, compared to the equal-length
   * window before it. Sorted by absolute {@code deltaMs} descending (worst regression first) and
   * capped at {@code limit}. A newly-active query (no prior load) has a {@code null} percent change
   * but still ranks on its absolute delta.
   */
  public List<TopMover> topMovers(long dbId, Instant now, Duration window, int limit) {
    Instant recentFrom = now.minus(window);
    Instant priorFrom = recentFrom.minus(window);
    return repo.windowTotals(dbId, priorFrom, recentFrom, now).stream()
        .map(
            w ->
                new TopMover(
                    w.queryid(),
                    w.normalizedText(),
                    w.recentTotalMs(),
                    w.priorTotalMs(),
                    w.recentTotalMs() - w.priorTotalMs(),
                    TrendMath.percentChange(w.recentTotalMs(), w.priorTotalMs())))
        .sorted(Comparator.comparingDouble(TopMover::deltaMs).reversed())
        .limit(limit)
        .toList();
  }

  /**
   * Queries that first appeared in the last {@code window} (no earlier {@code query_stats} history)
   * and whose total time in it is at least {@code minTotalMs}. Ordered by recent total time,
   * biggest first (the repository applies the threshold and ordering).
   */
  public List<NewSlowQuery> newSlowQueries(
      long dbId, Instant now, Duration window, double minTotalMs) {
    Instant recentFrom = now.minus(window);
    return repo.newQueries(dbId, recentFrom, now, minTotalMs).stream()
        .map(
            q ->
                new NewSlowQuery(
                    q.queryid(),
                    q.normalizedText(),
                    q.firstSeen(),
                    q.recentTotalMs(),
                    q.recentCalls(),
                    TrendMath.mean(q.recentTotalMs(), q.recentCalls())))
        .toList();
  }
}
