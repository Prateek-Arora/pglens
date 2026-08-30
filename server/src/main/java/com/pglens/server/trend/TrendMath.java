package com.pglens.server.trend;

/**
 * The pure derived-number math behind the trend queries, split out so its honesty boundaries are
 * unit-tested with no database (charter principle #1: never fabricate a number). Everything here is
 * a plain function of already-measured inputs.
 */
public final class TrendMath {

  private TrendMath() {}

  /**
   * Percent change of {@code recent} relative to {@code prior}, or {@code null} when {@code prior}
   * is not a positive baseline to divide by. A ratio against a zero (or absent) prior would be a
   * fabricated infinity, so callers fall back to the absolute delta for those movers instead.
   */
  public static Double percentChange(double recent, double prior) {
    if (prior <= 0.0) {
      return null;
    }
    return (recent - prior) / prior * 100.0;
  }

  /**
   * Mean ms per call over a window ({@code totalMs / calls}), or {@code null} when there were no
   * calls — 0/0 is undefined, never reported as 0. Same formula as the per-interval derived mean
   * (ADR-0024), applied to a window's summed counters.
   */
  public static Double mean(double totalMs, long calls) {
    return calls <= 0 ? null : totalMs / calls;
  }
}
