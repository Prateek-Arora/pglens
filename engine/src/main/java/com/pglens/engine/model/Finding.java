package com.pglens.engine.model;

import java.util.List;

/**
 * One anti-pattern the detector found in a plan: which rule fired, the table and column(s) it
 * implicates (the eventual index target, in order), a confidence, and a human-readable evidence
 * sentence built from the plan's own numbers. Pure model — no I/O.
 *
 * <p>Findings are <em>candidates for</em> a recommendation, not recommendations themselves: HypoPG
 * validation downstream is the decisive gate.
 */
public record Finding(
    String ruleId,
    String title,
    String table,
    List<String> columns,
    Confidence confidence,
    String evidence) {

  /**
   * How strongly the plan evidence supports the finding (drives ordering + phrasing, not gating).
   */
  public enum Confidence {
    HIGH,
    MEDIUM,
    LOW
  }

  public Finding {
    columns = columns == null ? List.of() : List.copyOf(columns);
  }
}
