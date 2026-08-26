package com.pglens.engine.model;

/**
 * A planner-validated recommendation placed in the cross-query ranking (ADR-0017). Pure model — no
 * I/O.
 *
 * <p>{@code estimatedMsSaved} is the ranking score: the query's real total exec time (pgss) × the
 * HypoPG generic-plan relative cost drop — a real weight scaled by an estimate, always labeled an
 * estimate, never measured savings. When {@code subsumed} is true, this index's key columns are a
 * prefix of {@code subsumedBy}'s (same table + access method), so it is flagged (not dropped) as
 * already served by that more general index.
 */
public record RankedRecommendation(
    long queryId,
    double queryTotalExecTimeMs,
    Recommendation recommendation,
    double estimatedMsSaved,
    boolean subsumed,
    String subsumedBy) {

  public IndexCandidate candidate() {
    return recommendation.candidate();
  }

  /** A distinct index worth creating: not already covered by a more general recommended index. */
  public boolean actionable() {
    return !subsumed;
  }
}
