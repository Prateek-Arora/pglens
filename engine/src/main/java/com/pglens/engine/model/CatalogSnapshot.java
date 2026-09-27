package com.pglens.engine.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A read-only snapshot of the catalog facts the pure analyzer needs — one {@link TableInfo} per
 * user table, keyed by the table's identity ({@link SqlIdent#table}: {@code orders}, {@code
 * app."UserAccounts"}), case-insensitively. {@link com.pglens.engine.db.CatalogReader} builds it
 * from the database; the detector rules consume it offline, so detection stays pure and
 * fixture-testable.
 */
public record CatalogSnapshot(Map<String, TableInfo> tables) {

  public CatalogSnapshot {
    Map<String, TableInfo> byKey = new LinkedHashMap<>();
    if (tables != null) {
      tables.forEach((name, info) -> byKey.put(key(name), info));
    }
    tables = Collections.unmodifiableMap(byKey);
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

  /** The root table of {@code table}'s partition tree, when {@code table} is a partition. */
  public Optional<String> partitionRoot(String name) {
    return table(name).map(TableInfo::partitionRoot);
  }

  /** True if {@code table} has an index whose leading key column is {@code column}. */
  public boolean hasIndexLeadingWith(String table, String column) {
    return table(table).map(t -> t.hasIndexLeadingWith(column)).orElse(false);
  }

  private static String key(String name) {
    return name.toLowerCase(Locale.ROOT);
  }
}
