package com.pglens.engine.model;

import java.util.List;

/**
 * One analyzed statement, and the per-query shape of the {@code --json} contract: its {@code
 * pg_stat_statements} identity and <em>real measured</em> stats (calls, total/mean exec time), the
 * captured generic plan ({@code null} when the statement could not be safely explained — utility
 * statements, generic-plan rejects), the anti-pattern findings, and one {@link Recommendation} per
 * candidate. Recommendations are all carried — validated, suppressed, and not-planner-validated
 * alike — so the report is honest about everything that was tried, not just the wins. Pure model —
 * no I/O.
 */
public record QueryReport(
    long queryId,
    String normalizedText,
    boolean truncated,
    long calls,
    double totalExecMs,
    double meanExecMs,
    boolean planCaptured,
    PlanSummary plan,
    List<Finding> findings,
    List<Recommendation> recommendations) {

  public QueryReport {
    findings = findings == null ? List.of() : List.copyOf(findings);
    recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
  }
}
