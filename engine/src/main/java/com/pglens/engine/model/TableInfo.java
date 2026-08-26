package com.pglens.engine.model;

import java.util.List;

/**
 * The catalog facts the analyzer needs about one table: its estimated live-row count ({@code
 * reltuples}; -1 means never analyzed / unknown) and its indexes. Pure model — no I/O.
 */
public record TableInfo(String name, long reltuples, List<IndexInfo> indexes) {

  public TableInfo {
    indexes = indexes == null ? List.of() : List.copyOf(indexes);
  }

  /** True if some index's leading key column is {@code column} (case-insensitive). */
  public boolean hasIndexLeadingWith(String column) {
    if (column == null) {
      return false;
    }
    for (IndexInfo ix : indexes) {
      String lead = ix.leadingColumn();
      if (lead != null && lead.equalsIgnoreCase(column)) {
        return true;
      }
    }
    return false;
  }
}
