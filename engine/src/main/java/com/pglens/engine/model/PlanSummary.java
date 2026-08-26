package com.pglens.engine.model;

/**
 * The captured plan for one query: how it was captured, its estimated total cost, and the typed
 * node tree. {@code captureMode} is always {@code "generic_plan"} in Phase 1 — the plan is produced
 * by {@code EXPLAIN (GENERIC_PLAN)} without executing the query, so the cost is a planner estimate,
 * not a runtime measurement. Pure model — no I/O.
 */
public record PlanSummary(String captureMode, double totalCost, PlanNode root) {

  public static final String GENERIC_PLAN = "generic_plan";

  /** Wraps a generic-plan root node, taking the root's total cost as the plan cost. */
  public static PlanSummary genericPlan(PlanNode root) {
    return new PlanSummary(GENERIC_PLAN, root == null ? 0.0 : root.totalCost(), root);
  }
}
