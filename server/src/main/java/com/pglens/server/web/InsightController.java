package com.pglens.server.web;

import com.pglens.server.advice.RecommendationReadService;
import com.pglens.server.advice.RecommendationReadService.AcrossDatabases;
import com.pglens.server.advice.RecommendationReadService.Hygiene;
import com.pglens.server.advice.RecommendationReadService.Recommendations;
import com.pglens.server.databases.DatabaseService;
import com.pglens.server.persistence.MonitoredDb;
import com.pglens.server.trend.TrendMath;
import com.pglens.server.trend.TrendService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Database-level reads (ADR-0044): recommendations (per database and across all), index hygiene,
 * top movers and newly-appeared slow queries.
 */
@RestController
class InsightController {

  /**
   * A query whose total time changed most versus the previous equal-length window. {@code
   * pctChange} is null when there was no prior load (a ratio against zero is not a number).
   */
  record TopMoverView(
      String queryid,
      String sql,
      double measuredRecentTotalMs,
      double measuredPriorTotalMs,
      double deltaMs,
      Double pctChange) {}

  /** {@code from} is the recent window's real start (a UTC hour); the prior window is as long. */
  record TopMovers(
      String database, String window, Instant from, Instant to, List<TopMoverView> items) {}

  /** A query first seen in the window (no earlier history) whose total time crosses the floor. */
  record NewSlowView(
      String queryid,
      String sql,
      Instant firstSeen,
      double measuredTotalMs,
      long calls,
      Double measuredMeanMs) {}

  record NewSlow(
      String database,
      String window,
      Instant from,
      Instant to,
      double minTotalMs,
      List<NewSlowView> items) {}

  private final DatabaseService databases;
  private final RecommendationReadService recommendations;
  private final TrendService trends;
  private final Clock clock;

  InsightController(
      DatabaseService databases,
      RecommendationReadService recommendations,
      TrendService trends,
      Clock clock) {
    this.databases = databases;
    this.recommendations = recommendations;
    this.trends = trends;
    this.clock = clock;
  }

  @GetMapping("/api/v1/databases/{db}/recommendations")
  Recommendations recommendations(@PathVariable String db) {
    return recommendations.forDatabase(databases.resolve(db));
  }

  /** The actionable indexes with the largest estimated saving, across every database. */
  @GetMapping("/api/v1/recommendations")
  AcrossDatabases acrossDatabases(@RequestParam(defaultValue = "20") int limit) {
    return recommendations.acrossDatabases(databases.all(), ApiParams.limit(limit, 200));
  }

  /** Existing indexes worth reviewing for removal (unused, duplicate, redundant). */
  @GetMapping("/api/v1/databases/{db}/hygiene")
  Hygiene hygiene(@PathVariable String db) {
    return recommendations.hygiene(databases.resolve(db));
  }

  @GetMapping("/api/v1/databases/{db}/top-movers")
  TopMovers topMovers(
      @PathVariable String db,
      @RequestParam(defaultValue = "7d") String window,
      @RequestParam(defaultValue = "20") int limit) {
    Duration length = ApiParams.window(window);
    int max = ApiParams.limit(limit, 200);
    MonitoredDb m = databases.resolve(db);
    Instant now = clock.instant();
    return new TopMovers(
        m.name(),
        window,
        TrendMath.windowStart(now, length),
        now,
        trends.topMovers(m.id(), now, length, max).stream()
            .map(
                t ->
                    new TopMoverView(
                        Long.toString(t.queryid()),
                        t.normalizedText(),
                        t.recentTotalMs(),
                        t.priorTotalMs(),
                        t.deltaMs(),
                        t.pctChange()))
            .toList());
  }

  @GetMapping("/api/v1/databases/{db}/new-slow")
  NewSlow newSlow(
      @PathVariable String db,
      @RequestParam(defaultValue = "24h") String window,
      @RequestParam(defaultValue = "1000") double minTotalMs,
      @RequestParam(defaultValue = "50") int limit) {
    Duration length = ApiParams.window(window);
    int max = ApiParams.limit(limit, 200);
    MonitoredDb m = databases.resolve(db);
    Instant now = clock.instant();
    return new NewSlow(
        m.name(),
        window,
        TrendMath.windowStart(now, length),
        now,
        minTotalMs,
        trends.newSlowQueries(m.id(), now, length, minTotalMs).stream()
            .limit(max)
            .map(
                q ->
                    new NewSlowView(
                        Long.toString(q.queryid()),
                        q.normalizedText(),
                        q.firstSeen(),
                        q.recentTotalMs(),
                        q.recentCalls(),
                        q.meanMs()))
            .toList());
  }
}
