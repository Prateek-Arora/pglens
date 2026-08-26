package com.pglens.engine.detect;

import com.pglens.engine.detect.PlanColumns.QualifiedColumn;
import com.pglens.engine.model.Finding;
import com.pglens.engine.model.Finding.Confidence;
import com.pglens.engine.model.PlanNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R4 — an explicit Sort feeding a Limit: a top-N query paying to sort a large input that an index
 * in the sort order could serve directly (returning rows pre-ordered, so the Limit stops early).
 * Flags the sort key's table and columns, in order, unless an index already leads with the first
 * one.
 */
final class SortLimitRule implements Rule {

  @Override
  public String id() {
    return "R4";
  }

  @Override
  public List<Finding> evaluate(PlanContext ctx) {
    List<Finding> findings = new ArrayList<>();
    for (PlanNode node : ctx.plan.flatten()) {
      if (!"Limit".equals(node.nodeType())) {
        continue;
      }
      PlanNode sort =
          node.flatten().stream()
              .filter(n -> n != node && n.isSort() && !n.sortKeys().isEmpty())
              .findFirst()
              .orElse(null);
      if (sort == null) {
        continue;
      }
      // Group sort columns by their table, preserving sort order (usually one table for a top-N).
      Map<String, List<String>> byTable = new LinkedHashMap<>();
      for (String key : sort.sortKeys()) {
        QualifiedColumn qc = PlanColumns.sortColumn(key);
        String table = qc == null ? null : ctx.resolveTable(qc.qualifier());
        if (table != null) {
          byTable.computeIfAbsent(table, k -> new ArrayList<>()).add(qc.column());
        }
      }
      byTable.forEach(
          (table, columns) -> {
            if (!ctx.catalog.hasIndexLeadingWith(table, columns.get(0))) {
              findings.add(
                  new Finding(
                      id(),
                      "Sort feeding a Limit (top-N could use an ordered index)",
                      table,
                      columns,
                      Confidence.MEDIUM,
                      "Top-N sorts %s by %s (sort node cost %.2f); an index in that order avoids the sort."
                          .formatted(table, String.join(", ", columns), sort.totalCost())));
            }
          });
    }
    return findings;
  }
}
