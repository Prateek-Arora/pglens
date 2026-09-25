package com.pglens.engine.estimate;

import com.pglens.engine.model.ValueRangeEstimate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns per-value planner costs (without / with a hypothetical index) into a {@link
 * ValueRangeEstimate} (ADR-0038). Pure — no I/O; the edge validator does the planning.
 */
public final class ValueRanges {

  private ValueRanges() {}

  /**
   * One sampled value's planner costs. {@code column} is the {@code table.column} whose parameter
   * it replaced; {@code frequency} is its {@code pg_stats} MCV frequency ({@code null} for the
   * histogram-median "typical" value). A cost is {@code null} when that variant could not be
   * planned — it is then left out, never guessed.
   */
  public record Sample(String column, Double frequency, Double costBefore, Double costAfter) {}

  /**
   * The range across the usable samples: worst (lowest) and best relative drop. Empty if no sample
   * has both costs with a positive before-cost.
   *
   * @param genericDrop the generic-plan drop, quoted in the label for comparison
   * @param gate the validation threshold — a worst case below it adds a caution to the label
   */
  public static Optional<ValueRangeEstimate> of(
      List<Sample> samples, double genericDrop, double gate) {
    Sample worst = null;
    double worstDrop = Double.POSITIVE_INFINITY;
    double bestDrop = Double.NEGATIVE_INFINITY;
    int usable = 0;
    for (Sample s : samples) {
      if (s.costBefore() == null || s.costAfter() == null || s.costBefore() <= 0) {
        continue;
      }
      usable++;
      double drop = (s.costBefore() - s.costAfter()) / s.costBefore();
      if (drop < worstDrop) {
        worstDrop = drop;
        worst = s;
      }
      bestDrop = Math.max(bestDrop, drop);
    }
    if (worst == null) {
      return Optional.empty();
    }
    List<String> columns = samples.stream().map(Sample::column).distinct().toList();
    return Optional.of(
        new ValueRangeEstimate(
            worst.column(),
            usable,
            worstDrop,
            worst.frequency(),
            bestDrop,
            label(
                String.join(", ", columns),
                worst.column(),
                usable,
                worstDrop,
                worst.frequency(),
                bestDrop,
                genericDrop,
                gate)));
  }

  private static String label(
      String columns,
      String worstColumn,
      int usable,
      double worst,
      Double worstFrequency,
      double best,
      double generic,
      double gate) {
    String whose =
        worstFrequency == null
            ? "a typical value of " + worstColumn
            : "a common value of %s (%s of rows)".formatted(worstColumn, pct(worstFrequency));
    String label =
        ("Across %d sampled values of %s (HypoPG planner estimates): %s for %s, up to %s "
                + "(generic plan: %s, which is what the ranking uses).")
            .formatted(usable, columns, drop(worst), whose, drop(best), drop(generic));
    if (worst < gate) {
      label +=
          " Caution: for some common values the planner expects little benefit — the real win"
              + " depends on which values your queries use.";
    }
    return label;
  }

  private static String pct(double frac) {
    return String.format(Locale.US, "%.1f%%", frac * 100);
  }

  /** A relative cost drop as "−56.7%"; a cost increase (negative drop) as "+4.0%". */
  private static String drop(double frac) {
    return (frac < 0 ? "+" : "−") + pct(Math.abs(frac));
  }
}
