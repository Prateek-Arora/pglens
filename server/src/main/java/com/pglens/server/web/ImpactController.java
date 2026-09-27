package com.pglens.server.web;

import com.pglens.server.databases.DatabaseService;
import com.pglens.server.impact.AppliedReadService;
import com.pglens.server.impact.AppliedReadService.Applied;
import com.pglens.server.impact.OverviewService;
import com.pglens.server.impact.OverviewService.DbTimeline;
import com.pglens.server.impact.OverviewService.Overview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The impact reads (ADR-0052): the overview across databases, a database's hourly timeline, and the
 * recommendations that were applied with their measured before/after (ADR-0051).
 */
@RestController
class ImpactController {

  private final DatabaseService databases;
  private final OverviewService overview;
  private final AppliedReadService applied;

  ImpactController(
      DatabaseService databases, OverviewService overview, AppliedReadService applied) {
    this.databases = databases;
    this.overview = overview;
    this.applied = applied;
  }

  /** Every database's measured time in the window, the estimated saving, and what to do next. */
  @GetMapping("/api/v1/overview")
  Overview overview(@RequestParam(defaultValue = "24h") String window) {
    return overview.overview(window, ApiParams.window(window));
  }

  /** A database's measured time per UTC hour, split into its top queries and everything else. */
  @GetMapping("/api/v1/databases/{db}/timeline")
  DbTimeline timeline(
      @PathVariable String db,
      @RequestParam(defaultValue = "24h") String window,
      @RequestParam(defaultValue = "5") int series) {
    return overview.timeline(
        databases.resolve(db), window, ApiParams.window(window), ApiParams.limit(series, 10));
  }

  /** Recommended indexes that now exist, with each query's measured time before and after. */
  @GetMapping("/api/v1/databases/{db}/applied")
  Applied applied(@PathVariable String db) {
    return applied.forDatabase(databases.resolve(db));
  }
}
