package com.pglens.engine.detect;

/** Tunable thresholds shared by the anti-pattern rules, kept in one place (ADR-0021). */
final class DetectionThresholds {

  private DetectionThresholds() {}

  /** Below this row count a seq scan is cheap enough that an index rarely helps — skip it. */
  static final long MIN_TABLE_ROWS = 500;

  /** At/above this row count an unindexed scan or join is treated as high-confidence. */
  static final long LARGE_TABLE = 100_000;

  /** Estimated selectivity at/below which a seq-scan filter is high-confidence for an index. */
  static final double HIGH_CONFIDENCE_SELECTIVITY = 0.01;

  /** Upper bound of the medium-confidence selectivity band (above it → low confidence). */
  static final double MEDIUM_CONFIDENCE_SELECTIVITY = 0.10;
}
