package com.pglens.server.web;

import com.pglens.server.databases.DatabaseService;
import com.pglens.server.explain.ExplanationReader;
import com.pglens.server.explain.ExplanationReader.ExplanationView;
import com.pglens.server.persistence.MonitoredDb;
import com.pglens.server.queries.QueryReadService;
import com.pglens.server.queries.QueryReadService.Leaderboard;
import com.pglens.server.queries.QueryReadService.QueryDetail;
import com.pglens.server.queries.QueryReadService.Trend;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-query reads (ADR-0044): the leaderboard, one query in detail, its trend and its explanations.
 * {@code queryid} is a string in the path and in every payload.
 */
@RestController
@RequestMapping("/api/v1/databases/{db}/queries")
class QueryController {

  record Explanations(String database, String queryid, List<ExplanationView> explanations) {}

  private final DatabaseService databases;
  private final QueryReadService queries;
  private final ExplanationReader explanations;
  private final Clock clock;

  QueryController(
      DatabaseService databases,
      QueryReadService queries,
      ExplanationReader explanations,
      Clock clock) {
    this.databases = databases;
    this.queries = queries;
    this.explanations = explanations;
    this.clock = clock;
  }

  /** The queries that ran in the window, by total time (or mean time, or calls). */
  @GetMapping
  Leaderboard leaderboard(
      @PathVariable String db,
      @RequestParam(defaultValue = "24h") String window,
      @RequestParam(defaultValue = "total") String sort,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    Duration length = ApiParams.window(window);
    return queries.leaderboard(
        databases.resolve(db).id(),
        window,
        length,
        ApiParams.sort(sort),
        ApiParams.limit(limit, 200),
        ApiParams.offset(offset));
  }

  @GetMapping("/{queryid}")
  QueryDetail detail(
      @PathVariable String db,
      @PathVariable String queryid,
      @RequestParam(defaultValue = "24h") String window) {
    Duration length = ApiParams.window(window);
    long id = ApiParams.queryId(queryid);
    MonitoredDb m = databases.resolve(db);
    return queries.detail(m.id(), m.name(), id, window, length);
  }

  /**
   * The query's intervals in {@code [from, to)}; default: the last 24 hours. {@code
   * resolution=raw|hour|auto} — auto: raw up to 48 hours, hourly beyond.
   */
  @GetMapping("/{queryid}/trend")
  Trend trend(
      @PathVariable String db,
      @PathVariable String queryid,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "auto") String resolution) {
    long id = ApiParams.queryId(queryid);
    QueryReadService.Resolution r = ApiParams.resolution(resolution);
    Instant end = to == null ? clock.instant() : ApiParams.instant(to, "to");
    Instant start =
        from == null ? end.minus(Duration.ofHours(24)) : ApiParams.instant(from, "from");
    ApiParams.span(start, end);
    MonitoredDb m = databases.resolve(db);
    return queries.trend(m.id(), m.name(), id, start, end, r);
  }

  /**
   * One explanation per index recommended for the query — the template unless a checked LLM
   * explanation of exactly the same facts is cached.
   */
  @GetMapping("/{queryid}/explanation")
  Explanations explanation(@PathVariable String db, @PathVariable String queryid) {
    long id = ApiParams.queryId(queryid);
    MonitoredDb m = databases.resolve(db);
    queries.requireQuery(m.id(), m.name(), id);
    return new Explanations(m.name(), queryid, explanations.forQuery(m.id(), id));
  }
}
