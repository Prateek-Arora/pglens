package com.pglens.engine.detect;

import com.pglens.engine.detect.PlanColumns.QualifiedColumn;
import com.pglens.engine.model.Finding;
import com.pglens.engine.model.Finding.Confidence;
import com.pglens.engine.model.PlanNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R1 — a sequential scan applying a filter on a column that no index leads with, on a table big
 * enough for the scan to matter. Liberal by design (selectivity only sets confidence, it does not
 * gate); HypoPG validation is the false-positive killer.
 */
final class SelectiveSeqScanRule implements Rule {

  @Override
  public String id() {
    return "R1";
  }

  @Override
  public List<Finding> evaluate(PlanContext ctx) {
    List<Finding> findings = new ArrayList<>();
    for (PlanNode node : ctx.plan.flatten()) {
      if (!node.isSeqScan() || node.filter() == null || node.relationName() == null) {
        continue;
      }
      long reltuples = ctx.catalog.reltuples(node.relationName());
      if (reltuples >= 0 && reltuples < DetectionThresholds.MIN_TABLE_ROWS) {
        continue;
      }

      Set<String> unindexed = new LinkedHashSet<>();
      for (QualifiedColumn qc : PlanColumns.predicateColumns(node.filter())) {
        String table = ctx.resolveTable(qc.qualifier());
        if (table != null
            && table.equalsIgnoreCase(node.relationName())
            && !ctx.catalog.hasIndexLeadingWith(table, qc.column())) {
          unindexed.add(qc.column());
        }
      }
      for (String column : unindexed) {
        findings.add(
            new Finding(
                id(),
                "Sequential scan with an unindexed filter",
                node.relationName(),
                List.of(column),
                confidence(node.planRows(), reltuples),
                evidence(node, column, reltuples)));
      }
    }
    return findings;
  }

  private static Confidence confidence(long planRows, long reltuples) {
    if (reltuples <= 0) {
      return Confidence.MEDIUM; // never analyzed — can't judge selectivity from row estimates
    }
    double selectivity = (double) planRows / reltuples;
    if (selectivity <= DetectionThresholds.HIGH_CONFIDENCE_SELECTIVITY) {
      return Confidence.HIGH;
    }
    return selectivity <= DetectionThresholds.MEDIUM_CONFIDENCE_SELECTIVITY
        ? Confidence.MEDIUM
        : Confidence.LOW;
  }

  private static String evidence(PlanNode node, String column, long reltuples) {
    return "Seq Scan on %s filters %s (est. %d of ~%s rows, total cost %.2f)."
        .formatted(
            node.relationName(),
            column,
            node.planRows(),
            reltuples < 0 ? "?" : Long.toString(reltuples),
            node.totalCost());
  }
}
