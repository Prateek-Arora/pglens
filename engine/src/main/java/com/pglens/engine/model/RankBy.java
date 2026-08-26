package com.pglens.engine.model;

import java.util.Locale;

/** How to rank statements read from {@code pg_stat_statements}. */
public enum RankBy {
  /** Total accumulated execution time — "where the pain is" (the default). */
  TOTAL_TIME,
  /** Average time per call — surfaces individually slow statements. */
  MEAN_TIME,
  /** Call count — surfaces the hottest statements. */
  CALLS;

  /**
   * Maps a CLI {@code --order-by} value (total|mean|calls) to a {@link RankBy}; null → TOTAL_TIME.
   */
  public static RankBy fromCli(String value) {
    if (value == null) {
      return TOTAL_TIME;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "total", "total_time", "total-time" -> TOTAL_TIME;
      case "mean", "mean_time", "mean-time", "avg" -> MEAN_TIME;
      case "calls", "count" -> CALLS;
      default ->
          throw new IllegalArgumentException(
              "Unknown --order-by '" + value + "'. Use: total | mean | calls.");
    };
  }
}
