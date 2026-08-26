package com.pglens.engine.candidate;

import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.Finding;
import com.pglens.engine.model.IndexCandidate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a query's {@link Finding}s into candidate {@code CREATE INDEX} statements. Pure — no I/O.
 *
 * <p>Per ADR-0015 the composite index is built here, not in a detector rule: when a table has both
 * a filter finding (R1) and a sort finding (R4), they merge into one composite whose <b>equality
 * column(s) lead and the sort column(s) follow</b> (the concatenated-index rule) — and the
 * redundant single-column candidates the composite already covers are dropped. Join keys (R3) and
 * filter/sort-only cases yield single-purpose candidates.
 *
 * <p>Scalar candidates (equality/range/order-by, join keys) are btree. R7 containment/existence
 * findings become <b>GIN</b> candidates on the filtered column — a separate access method, so they
 * never merge into a btree composite; {@link IndexCandidate#of} routes them to the
 * not-planner-validatable bucket (HypoPG cannot simulate GIN).
 */
public final class IndexCandidateGenerator {

  /** Candidates for one query, derived from that query's findings (empty in → empty out). */
  public List<IndexCandidate> generate(List<Finding> findings) {
    if (findings == null || findings.isEmpty()) {
      return List.of();
    }

    Map<String, TableFindings> byTable = new LinkedHashMap<>();
    for (Finding f : findings) {
      byTable.computeIfAbsent(f.table(), t -> new TableFindings()).add(f);
    }

    List<IndexCandidate> candidates = new ArrayList<>();
    byTable.forEach((table, tf) -> tf.emit(table, candidates));
    return candidates;
  }

  /** Accumulates one table's filter/join/sort columns as findings arrive, then emits candidates. */
  private static final class TableFindings {
    private final Set<String> filterColumns = new LinkedHashSet<>(); // R1
    private final Set<String> joinColumns = new LinkedHashSet<>(); // R3
    private final List<String> sortColumns = new ArrayList<>(); // R4 (order matters)
    private final Set<String> ginColumns = new LinkedHashSet<>(); // R7 (GIN-servable operators)

    void add(Finding f) {
      switch (f.ruleId()) {
        case "R1" -> filterColumns.addAll(f.columns());
        case "R3" -> joinColumns.addAll(f.columns());
        case "R4" -> {
          for (String c : f.columns()) {
            if (!sortColumns.contains(c)) {
              sortColumns.add(c);
            }
          }
        }
        case "R7" -> ginColumns.addAll(f.columns());
        default -> {
          /* rule contributes no index candidate */
        }
      }
    }

    void emit(String table, List<IndexCandidate> out) {
      Set<String> covered = new LinkedHashSet<>();

      // Composite: equality filter column(s) lead, sort column(s) follow.
      if (!filterColumns.isEmpty() && !sortColumns.isEmpty()) {
        List<String> columns = new ArrayList<>(filterColumns);
        for (String s : sortColumns) {
          if (!columns.contains(s)) {
            columns.add(s);
          }
        }
        out.add(
            IndexCandidate.of(
                table,
                columns,
                AccessMethod.BTREE,
                List.of("R1", "R4"),
                "Equality on "
                    + String.join(", ", filterColumns)
                    + " then order by "
                    + String.join(", ", sortColumns)));
        covered.addAll(columns);
      }

      // Remaining single-column filter candidates not already covered by the composite prefix.
      for (String column : filterColumns) {
        if (covered.add(column)) {
          out.add(
              IndexCandidate.of(
                  table,
                  List.of(column),
                  AccessMethod.BTREE,
                  List.of("R1"),
                  "Selective filter on " + column));
        }
      }

      // Unindexed join keys.
      for (String column : joinColumns) {
        if (covered.add(column)) {
          out.add(
              IndexCandidate.of(
                  table,
                  List.of(column),
                  AccessMethod.BTREE,
                  List.of("R3"),
                  "Unindexed join key " + column));
        }
      }

      // Sort with no filter to merge into: an ordered index on the sort key(s) alone.
      if (filterColumns.isEmpty() && !sortColumns.isEmpty()) {
        out.add(
            IndexCandidate.of(
                table,
                new ArrayList<>(sortColumns),
                AccessMethod.BTREE,
                List.of("R4"),
                "Order by " + String.join(", ", sortColumns)));
      }

      // Containment/existence filters: a GIN index per column (separate access method — never
      // merged into a btree composite; HypoPG cannot planner-validate it).
      for (String column : ginColumns) {
        out.add(
            IndexCandidate.of(
                table,
                List.of(column),
                AccessMethod.GIN,
                List.of("R7"),
                "Containment/existence filter on " + column + " (GIN-servable)"));
      }
    }
  }
}
