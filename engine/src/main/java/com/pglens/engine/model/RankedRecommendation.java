package com.pglens.engine.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A planner-validated recommendation placed in the cross-query ranking (ADR-0017, ADR-0038). Pure
 * model — no I/O.
 *
 * <p>{@code estimatedMsSaved} is the ranking score: the query's real total exec time (pgss) × a
 * HypoPG relative cost drop — the value-range <b>worst case</b> when one exists, else the generic
 * plan's ({@code scoreBasis} says which; see {@code RankingScore}). A real weight scaled by an
 * estimate, always labeled an estimate, never measured savings.
 *
 * <p>When {@code subsumed} is true, this index's key columns are a prefix of a more general
 * recommended index ({@code subsumedBy} / {@code subsumedByDdl}, same table + access method). That
 * is only a structural hint: {@code coverage} records whether the general index was actually
 * HypoPG-validated against <em>this</em> rec's query ({@code null} until checked). Only a passing
 * check makes this rec redundant; a failing one keeps it actionable.
 */
public record RankedRecommendation(
    long queryId,
    double queryTotalExecTimeMs,
    Recommendation recommendation,
    double estimatedMsSaved,
    ScoreBasis scoreBasis,
    boolean subsumed,
    String subsumedBy,
    String subsumedByDdl,
    ValidationResult coverage) {

  public IndexCandidate candidate() {
    return recommendation.candidate();
  }

  /**
   * A distinct index worth creating: not subsumed, or subsumed by an index that failed this query's
   * validation (so it doesn't actually serve it).
   */
  @JsonProperty("actionable") // derived, but part of the --json 1.1 contract
  public boolean actionable() {
    return !subsumed || (coverage != null && !coverage.isRecommended());
  }

  /** True when a more general recommended index was validated against this rec's query. */
  @JsonProperty("coveredBySubsumer")
  public boolean coveredBySubsumer() {
    return subsumed && coverage != null && coverage.isRecommended();
  }

  /** This rec with its coverage check attached. */
  public RankedRecommendation withCoverage(ValidationResult check) {
    return new RankedRecommendation(
        queryId,
        queryTotalExecTimeMs,
        recommendation,
        estimatedMsSaved,
        scoreBasis,
        subsumed,
        subsumedBy,
        subsumedByDdl,
        check);
  }
}
