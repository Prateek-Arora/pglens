package com.pglens.engine.model;

import java.util.Locale;

/**
 * Index access method. {@code hypoPgSupported} records whether HypoPG can simulate it for
 * planner-cost validation (btree/brin/hash/bloom) or not (GIN/GiST) — the latter are surfaced but
 * labeled <em>not planner-validated</em> (ADR-0005). Pure model — no I/O.
 */
public enum AccessMethod {
  BTREE(true),
  BRIN(true),
  HASH(true),
  BLOOM(true),
  GIN(false),
  GIST(false);

  private final boolean hypoPgSupported;

  AccessMethod(boolean hypoPgSupported) {
    this.hypoPgSupported = hypoPgSupported;
  }

  /**
   * True if HypoPG can build a hypothetical index of this method (so a cost delta is measurable).
   */
  public boolean hypoPgSupported() {
    return hypoPgSupported;
  }

  /** The lowercase name used in {@code CREATE INDEX ... USING <method>}. */
  public String sqlUsing() {
    return name().toLowerCase(Locale.ROOT);
  }
}
