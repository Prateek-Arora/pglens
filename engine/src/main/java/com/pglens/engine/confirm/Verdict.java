package com.pglens.engine.confirm;

/**
 * The measured outcome of building one recommended index on the copy (ADR-0042): the warm execution
 * time of the workload's own statements with the index vs without it. Thresholds are the
 * benchmark's (ADR-0040): <b>faster</b> at a drop of at least 15 % (PgLens's validation gate),
 * <b>slower</b> at 5 % or more added time; <b>no real effect</b> in between.
 */
public enum Verdict {
  FASTER,
  NO_REAL_EFFECT,
  SLOWER,
  /** The real {@code CREATE INDEX} failed on the copy (e.g. a key value too wide for a B-tree). */
  UNBUILDABLE,
  /**
   * Nothing to compare: no matched statement, every one failed, or a timeout blurred the result.
   */
  NOT_MEASURED;

  /** Drop at or above which an index made its statements faster (the validation gate). */
  public static final double FASTER_AT = 0.15;

  /** Drop at or below which it made them slower (5 % or more added time). */
  public static final double SLOWER_AT = -0.05;

  /** The verdict for a measured drop ({@code 1 − after / before}). */
  public static Verdict of(double drop) {
    if (drop >= FASTER_AT) {
      return FASTER;
    }
    return drop <= SLOWER_AT ? SLOWER : NO_REAL_EFFECT;
  }
}
