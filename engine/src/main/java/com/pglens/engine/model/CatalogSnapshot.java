package com.pglens.engine.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

  /**
   * True if PgLens can recommend an index on {@code table}: not a system relation ({@code
   * pg_catalog}, {@code information_schema}, TOAST or temp schemas — Postgres refuses indexes on
   * catalogs), and, when this snapshot lists the user tables, one of them. {@code pg_dump} and
   * admin tools put catalog queries into {@code pg_stat_statements}; advice on those is noise
   * (B27).
   */
  public boolean indexable(String table) {
    if (table == null) {
      return false;
    }
    String schema = SqlIdent.schemaName(table).toLowerCase(Locale.ROOT);
    if (SYSTEM_SCHEMAS.contains(schema)
        || schema.startsWith("pg_temp_")
        || schema.startsWith("pg_toast_temp_")) {
      return false;
    }
    // Unqualified catalog names (plans that carry no schema) are caught by name.
    if (SqlIdent.DEFAULT_SCHEMA.equals(schema)
        && SqlIdent.relationName(table).toLowerCase(Locale.ROOT).startsWith("pg_")
        && table(table).isEmpty()) {
      return false;
    }
    return tables.isEmpty() || table(table).isPresent();
  }

  private static final Set<String> SYSTEM_SCHEMAS =
      Set.of("pg_catalog", "information_schema", "pg_toast");

  private static String key(String name) {
    return name.toLowerCase(Locale.ROOT);
  }
}
