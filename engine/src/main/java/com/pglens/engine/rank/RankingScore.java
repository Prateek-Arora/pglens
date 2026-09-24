package com.pglens.engine.rank;

import com.pglens.engine.model.ScoreBasis;

/**
 * Which relative cost drop a recommendation is ranked by (ADR-0017, amended by ADR-0038). When a
 * value-range estimate exists, the <b>worst case</b> is used as a conservative floor — the win if
 * every call hit the heaviest common value — so generic-plan optimism on a skewed column can't push
 * an index up the "fix these first" list. Otherwise the generic-plan drop is used. Never negative.
 * Shared by the CLI's {@link Recommender} and the server's persisted score so they can't drift.
 * Pure — no I/O.
 */
public final class RankingScore {

  private RankingScore() {}

  /** The drop to rank by and its basis (carried on the rec so the report can say which). */
  public record Drop(double value, ScoreBasis basis) {}

  /**
   * The ranking drop: {@code min(generic, worstCase)} when a worst case exists, else {@code
   * generic}; a null generic counts as 0, and the result is clamped at 0.
   */
  public static Drop drop(Double genericDrop, Double worstCaseDrop) {
    double generic = genericDrop == null ? 0.0 : genericDrop;
    if (worstCaseDrop != null) {
      return new Drop(
          Math.max(0.0, Math.min(generic, worstCaseDrop)), ScoreBasis.VALUE_RANGE_FLOOR);
    }
    return new Drop(Math.max(0.0, generic), ScoreBasis.GENERIC_PLAN);
  }
}
