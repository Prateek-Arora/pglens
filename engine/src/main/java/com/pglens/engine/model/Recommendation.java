package com.pglens.engine.model;

/**
 * A candidate index paired with its HypoPG {@link ValidationResult} — what the recommender ranks
 * and the renderers print. A "recommendation" whose validation is SUPPRESSED or
 * NOT_PLANNER_VALIDATED is still carried (so the report can be honest about what was tried and
 * why), not silently dropped. Pure model — no I/O.
 */
public record Recommendation(IndexCandidate candidate, ValidationResult validation) {

  public boolean isRecommended() {
    return validation.isRecommended();
  }
}
