package com.pglens.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the numbers in a text with their magnitude and unit, normalized so that "~600k rows",
 * "600,572" and "0.6 million" compare equal, and "2.6 minutes" matches "153.3 s". The spike showed
 * a guard without units both misses invented numbers and flags correct conversions (§3a). Pure.
 */
final class Quantities {

  /** What a number measures; only the same dimension can match. */
  enum Dim {
    COUNT,
    PERCENT,
    TIME_MS,
    BYTES,
    MULTIPLIER
  }

  /** One number as written ({@code raw}) and normalized ({@code value} in the base unit). */
  record Quantity(double value, Dim dim, String raw) {}

  // A number not glued to an identifier ($1, R1, t1, 10c, v1.2) — then an optional unit.
  private static final Pattern NUMBER =
      Pattern.compile(
          "(?<![\\w.$/])(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d+)?"
              + "(?:\\s*(thousand|million|billion|mln|bn|[kK]|M|B(?![-\\w])"
              + "|%|percent|per cent"
              + "|milliseconds?|msecs?|ms|seconds?|secs?|s|minutes?|mins?|min|hours?|hrs?|h"
              + "|bytes?|[kK]i?B|[MGT]i?B"
              + "|×|x))?"
              + "(?![\\w])");

  private Quantities() {}

  static List<Quantity> scan(String text) {
    List<Quantity> out = new ArrayList<>();
    Matcher m = NUMBER.matcher(text);
    while (m.find()) {
      double base = Double.parseDouble(m.group(1).replace(",", "") + nz(m.group(2)));
      String unit = m.group(3);
      out.add(normalize(base, unit, m.group()));
    }
    return out;
  }

  private static Quantity normalize(double n, String unit, String raw) {
    if (unit == null) {
      return new Quantity(n, Dim.COUNT, raw);
    }
    return switch (unit) {
      case "thousand", "k", "K" -> new Quantity(n * 1e3, Dim.COUNT, raw);
      case "million", "mln", "M" -> new Quantity(n * 1e6, Dim.COUNT, raw);
      case "billion", "bn", "B" -> new Quantity(n * 1e9, Dim.COUNT, raw);
      case "%", "percent", "per cent" -> new Quantity(n, Dim.PERCENT, raw);
      case "ms", "msec", "msecs", "millisecond", "milliseconds" ->
          new Quantity(n, Dim.TIME_MS, raw);
      case "s", "sec", "secs", "second", "seconds" -> new Quantity(n * 1e3, Dim.TIME_MS, raw);
      case "min", "mins", "minute", "minutes" -> new Quantity(n * 6e4, Dim.TIME_MS, raw);
      case "h", "hr", "hrs", "hour", "hours" -> new Quantity(n * 3.6e6, Dim.TIME_MS, raw);
      case "×", "x" -> new Quantity(n, Dim.MULTIPLIER, raw);
      default -> bytes(n, unit, raw);
    };
  }

  private static Quantity bytes(double n, String unit, String raw) {
    char scale = Character.toUpperCase(unit.charAt(0));
    double factor =
        switch (scale) {
          case 'K' -> 1024d;
          case 'M' -> 1024d * 1024;
          case 'G' -> 1024d * 1024 * 1024;
          case 'T' -> 1024d * 1024 * 1024 * 1024;
          default -> 1d; // "byte(s)"
        };
    return new Quantity(n * factor, Dim.BYTES, raw);
  }

  /** True when {@code q} is within {@code tolerance} (relative) of some fact of the same kind. */
  static boolean supported(Quantity q, List<Quantity> facts, double tolerance) {
    for (Quantity f : facts) {
      if (f.dim() == q.dim()
          && Math.abs(q.value() - f.value()) <= tolerance * Math.max(Math.abs(f.value()), 1e-9)) {
        return true;
      }
    }
    return false;
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
