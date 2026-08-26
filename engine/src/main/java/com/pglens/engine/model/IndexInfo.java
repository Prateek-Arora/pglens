package com.pglens.engine.model;

import java.util.List;

/**
 * One existing index on a table, as read from the catalog. {@code columns} are the ordered key
 * columns (plain column names lowercased, or the expression text for expression keys). Pure model —
 * no I/O.
 */
public record IndexInfo(
    String table,
    String name,
    List<String> columns,
    boolean unique,
    boolean primary,
    String method,
    String predicate) {

  public IndexInfo {
    columns = columns == null ? List.of() : List.copyOf(columns);
  }

  /** A partial index (has a {@code WHERE} predicate) — not generic-plan-validatable (see plan). */
  public boolean isPartial() {
    return predicate != null && !predicate.isBlank();
  }

  /** The leading (first) key column, or null if the index has no columns. */
  public String leadingColumn() {
    return columns.isEmpty() ? null : columns.get(0);
  }
}
