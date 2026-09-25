package com.pglens.engine.rank;

import com.pglens.engine.model.ScoreBasis;

/**
 * Which relative cost drop a recommendation is ranked by (ADR-0017): the generic plan's, clamped at
 * 0. ADR-0038 ranked by the value-range worst case instead; ADR-0041 went back to the generic drop
 * after the pre-registered JOB/IMDB benchmark (ADR-0040) found the floor further from the measured
 * result on 6 of the 8 recommendations where the two differed. The worst case comes from a column's
 * most common value, and {@code pg_stat_statements} can't say whether the workload queries that
 * value — so the range is shown beside the score as evidence, not used to rank. Shared by the CLI's
 * {@link Recommender} and the server's persisted score so they can't drift. Pure — no I/O.
 */
public final class RankingScore {

  private RankingScore() {}

  /** The drop to rank by and its basis (carried on the rec so the report can say which). */
  public record Drop(double value, ScoreBasis basis) {}

  /** The ranking drop: the generic-plan drop, a null counting as 0, clamped at 0. */
  public static Drop drop(Double genericDrop) {
    double generic = genericDrop == null ? 0.0 : genericDrop;
    return new Drop(Math.max(0.0, generic), ScoreBasis.GENERIC_PLAN);
  }
}
