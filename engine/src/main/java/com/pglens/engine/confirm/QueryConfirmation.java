package com.pglens.engine.confirm;

/**
 * One report query's measured result under one index (ADR-0042): PgLens's generic-plan estimate
 * beside the measurement on the copy. {@code statements} is how many of the workload's statements
 * were measured for it (capped per query). Identified by the report's {@code queryId} only — no
 * statement text or value. Pure model.
 */
public record QueryConfirmation(
    long queryId,
    Double estimatedDrop,
    Verdict verdict,
    Double measuredDrop,
    Double beforeMs,
    Double afterMs,
    boolean afterAtLeast,
    int statements,
    int failed,
    String reason) {

  public static QueryConfirmation of(long queryId, Double estimatedDrop, Outcome o) {
    return new QueryConfirmation(
        queryId,
        estimatedDrop,
        o.verdict(),
        o.drop(),
        o.beforeMs(),
        o.afterMs(),
        o.afterAtLeast(),
        o.statements(),
        o.failed(),
        o.reason());
  }
}
