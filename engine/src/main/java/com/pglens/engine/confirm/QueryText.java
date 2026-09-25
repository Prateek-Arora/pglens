package com.pglens.engine.confirm;

import java.util.regex.Pattern;

/**
 * Matches a statement to a report query by pg_stat_statements' normalized text (ADR-0042). The
 * report's text comes from the monitored database's pg_stat_statements; the statement's from the
 * copy's, after replaying it once there — the same normalization on both sides, so the texts are
 * equal although the queryids differ (a restored copy has different relation OIDs, and queryid
 * hashes them). Pure — no I/O.
 */
public final class QueryText {

  // pg_stat_statements keeps the PREPARE around a statement replayed via PREPARE/EXECUTE.
  private static final Pattern PREPARE_PREFIX =
      Pattern.compile("^\\s*PREPARE\\s+pglens_s\\d+\\s+AS\\s+", Pattern.CASE_INSENSITIVE);
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private QueryText() {}

  /**
   * The text compared: PgLens's own PREPARE prefix removed, whitespace runs collapsed to one space
   * (pg_stat_statements keeps the first text it saw for a queryid, so two sends that differ only in
   * whitespace share one entry), trailing semicolon dropped.
   */
  public static String canonical(String pgssText) {
    String s = PREPARE_PREFIX.matcher(pgssText).replaceFirst("");
    s = WHITESPACE.matcher(s).replaceAll(" ").strip();
    return s.endsWith(";") ? s.substring(0, s.length() - 1).strip() : s;
  }

  /**
   * True if the copy's text is the report query's. A report text PgLens marked {@code truncated}
   * matches as a prefix.
   */
  public static boolean matches(String reportText, boolean truncated, String copyText) {
    String report = canonical(reportText);
    String copy = canonical(copyText);
    return truncated ? copy.startsWith(report) : copy.equals(report);
  }
}
