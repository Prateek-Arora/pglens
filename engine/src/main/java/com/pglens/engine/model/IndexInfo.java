package com.pglens.engine.model;

import java.util.List;

/**
 * One existing index on a table, as read from the catalog. {@code columns} are the ordered key
 * columns (plain column names lowercased, or the expression text for expression keys). Pure model —
 * no I/O.
 *
 * <p>The last three fields feed index <em>hygiene</em> (Phase 2, Step 6), not plan detection:
 *
 * <ul>
 *   <li>{@code definition} — the {@code pg_get_indexdef} DDL, for display in a hygiene finding.
 *   <li>{@code constraintBacked} — the index backs a PRIMARY KEY / UNIQUE / EXCLUSION constraint,
 *       or its leading columns cover a FOREIGN KEY's referencing columns. Either way it is
 *       <b>never</b> a drop candidate (the B5 safety invariant: never recommend dropping a
 *       unique/PK/FK/constraint index). Computed at the edge so a single boolean carries the whole
 *       guard downstream.
 *   <li>{@code idxScan} — the cumulative {@code pg_stat_user_indexes.idx_scan} at snapshot time.
 *       The server deltas it over the persisted window to judge "unused"; the pure detector ignores
 *       it.
 * </ul>
 */
public record IndexInfo(
    String table,
    String name,
    List<String> columns,
    boolean unique,
    boolean primary,
    String method,
    String predicate,
    String definition,
    boolean constraintBacked,
    long idxScan) {

  public IndexInfo {
    columns = columns == null ? List.of() : List.copyOf(columns);
    definition = definition == null ? "" : definition;
  }

  /**
   * Structural-only convenience for plan-detection fixtures and any caller that carries no hygiene
   * data. {@code constraintBacked} defaults to {@code unique || primary} (a unique/PK index is
   * constraint-backed by definition); {@code definition} is empty and {@code idxScan} is 0.
   */
  public IndexInfo(
      String table,
      String name,
      List<String> columns,
      boolean unique,
      boolean primary,
      String method,
      String predicate) {
    this(table, name, columns, unique, primary, method, predicate, "", unique || primary, 0L);
  }

  /** A partial index (has a {@code WHERE} predicate) — not generic-plan-validatable (see plan). */
  public boolean isPartial() {
    return predicate != null && !predicate.isBlank();
  }

  /** The leading (first) key column, or null if the index has no columns. */
  public String leadingColumn() {
    return columns.isEmpty() ? null : columns.get(0);
  }

  /**
   * True if this index must never be proposed for removal — it enforces (or speeds the enforcement
   * of) a constraint. The B5 safety invariant; the hygiene analyzer skips a guarded index entirely.
   */
  public boolean guarded() {
    return unique || primary || constraintBacked;
  }
}
