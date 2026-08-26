package com.pglens.engine.model;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A read-only snapshot of the catalog facts the pure analyzer needs — one {@link TableInfo} per
 * user table, keyed by lowercased table name. {@link com.pglens.engine.db.CatalogReader} builds it
 * from the database; the detector rules consume it offline, so detection stays pure and
 * fixture-testable.
 *
 * <p>MVP scope: tables are keyed by unqualified name (single-schema assumption). Schema
 * qualification is a later refinement.
 */
public record CatalogSnapshot(Map<String, TableInfo> tables) {

  public CatalogSnapshot {
    tables = tables == null ? Map.of() : Map.copyOf(tables);
  }

  /** An empty snapshot (no catalog available). */
  public static CatalogSnapshot empty() {
    return new CatalogSnapshot(Map.of());
  }

  public Optional<TableInfo> table(String name) {
    return name == null ? Optional.empty() : Optional.ofNullable(tables.get(key(name)));
  }

  /** Estimated live-row count for {@code table}, or -1 if unknown / never analyzed. */
  public long reltuples(String name) {
    return table(name).map(TableInfo::reltuples).orElse(-1L);
  }

  /** True if {@code table} has an index whose leading key column is {@code column}. */
  public boolean hasIndexLeadingWith(String table, String column) {
    return table(table).map(t -> t.hasIndexLeadingWith(column)).orElse(false);
  }

  private static String key(String name) {
    return name.toLowerCase(Locale.ROOT);
  }
}
