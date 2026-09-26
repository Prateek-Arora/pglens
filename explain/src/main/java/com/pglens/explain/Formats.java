package com.pglens.explain;

import java.util.Locale;

/**
 * Human formatting for the numbers an explanation may repeat. Facts reach the model already
 * formatted ("153.3 s", "9,761", "58.3%") so it copies them instead of converting — the spike's
 * wrong numbers were conversions (ADR-0043). Pure.
 */
public final class Formats {

  private Formats() {}

  /** A duration: "13.2 ms", "16.4 s", "153.3 s (about 2.6 min)", "5,000.0 s (about 1.4 h)". */
  public static String duration(double ms) {
    if (ms < 1_000) {
      return String.format(Locale.US, "%.1f ms", ms);
    }
    double s = ms / 1_000;
    if (s < 60) {
      return String.format(Locale.US, "%.1f s", s);
    }
    if (s < 3_600) {
      return String.format(Locale.US, "%,.1f s (about %.1f min)", s, s / 60);
    }
    return String.format(Locale.US, "%,.1f s (about %.1f h)", s, s / 3_600);
  }

  /** A count with thousands separators: "1,380,035". */
  public static String count(long n) {
    return String.format(Locale.US, "%,d", n);
  }

  /** A planner cost, rounded: "9,761". */
  public static String cost(double cost) {
    return count(Math.round(cost));
  }

  /** A fraction as a percentage with one decimal: 0.5829 → "58.3%". */
  public static String percent(double fraction) {
    return String.format(Locale.US, "%.1f%%", fraction * 100);
  }
}
