package com.pglens.engine.model;

import java.util.List;

/**
 * One anti-pattern the detector found in a plan: which rule fired, the table and column(s) it
 * implicates (the eventual index target, in order), a confidence, and a human-readable evidence
 * sentence built from the plan's own numbers. Pure model — no I/O.
 *
 * <p>Findings are <em>candidates for</em> a recommendation, not recommendations themselves: HypoPG
 * validation downstream is the decisive gate.
 *
 * @param planNode the plan node the finding is about, as its position in {@link PlanNode#flatten()}
 *     of the plan root (0 = the root); {@code null} when not tied to a node. Lets a plan viewer
 *     highlight the offending node (ADR-0044, {@code --json} 1.4).
 */
public record Finding(
    String ruleId,
    String title,
    String table,
    List<String> columns,
    Confidence confidence,
    String evidence,
    Integer planNode) {

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

  /** A finding not tied to a plan node. */
  public Finding(
      String ruleId,
      String title,
      String table,
      List<String> columns,
      Confidence confidence,
      String evidence) {
    this(ruleId, title, table, columns, confidence, evidence, null);
  }
}
