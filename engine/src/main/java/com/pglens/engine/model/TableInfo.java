package com.pglens.engine.model;

import java.util.List;

/**
 * The catalog facts the analyzer needs about one table: its identity ({@link SqlIdent#table}), its
 * estimated live-row count ({@code reltuples}; -1 means never analyzed / unknown), its indexes, its
 * cumulative read/write {@link TableActivity} ({@code null} when not read — e.g. a fixture or a
 * pre-2.5 server row), and — for a partition — the identity of its partition tree's root table,
 * where an index for it belongs (ADR-0049; {@code null} otherwise). A partitioned root itself is
 * listed too, with its partitions' rows and activity summed. Pure model — no I/O.
 */
public record TableInfo(
    String name,
    long reltuples,
    List<IndexInfo> indexes,
    TableActivity activity,
    String partitionRoot) {

  public TableInfo {
    indexes = indexes == null ? List.of() : List.copyOf(indexes);
  }

  /** A table that isn't a partition. */
  public TableInfo(String name, long reltuples, List<IndexInfo> indexes, TableActivity activity) {
    this(name, reltuples, indexes, activity, null);
  }

  /** A table without activity counters (detector fixtures, and callers that don't need them). */
  public TableInfo(String name, long reltuples, List<IndexInfo> indexes) {
    this(name, reltuples, indexes, null, null);
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
