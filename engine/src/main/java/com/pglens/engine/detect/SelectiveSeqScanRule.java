package com.pglens.engine.detect;

import com.pglens.engine.detect.PlanColumns.QualifiedColumn;
import com.pglens.engine.model.Finding;
import com.pglens.engine.model.Finding.Confidence;
import com.pglens.engine.model.PlanNode;
import com.pglens.engine.model.SqlIdent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R1 — a sequential scan applying a filter on a column that no index leads with, on a table big
 * enough for the scan to matter (a parallel scan too). On a partition the finding targets the
 * partition tree's root table. Liberal by design (selectivity only sets confidence, it does not
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
      String scanned = node.table();
      if (!node.isSeqScan() || node.filter() == null || scanned == null) {
        continue;
      }
      long reltuples = ctx.catalog.reltuples(scanned);
      if (reltuples >= 0 && reltuples < DetectionThresholds.MIN_TABLE_ROWS) {
        continue;
      }

      Set<String> unindexed = new LinkedHashSet<>();
      for (QualifiedColumn qc : PlanColumns.predicateColumns(node.filter())) {
        String table = ctx.resolveTable(qc.qualifier());
        if (table != null
            && table.equals(scanned)
            && !ctx.catalog.hasIndexLeadingWith(table, qc.column())) {
          unindexed.add(qc.column());
        }
      }
      for (String column : unindexed) {
        findings.add(
            new Finding(
                id(),
                "Sequential scan with an unindexed filter",
                ctx.indexTarget(scanned),
                List.of(column),
                confidence(node.planRows(), reltuples),
                evidence(node, scanned, column, reltuples),
                ctx.nodeId(node)));
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

  private static String evidence(PlanNode node, String table, String column, long reltuples) {
    return "%s on %s filters %s (est. %d of ~%s rows, total cost %.2f)."
        .formatted(
            node.parallelAware() ? "Parallel Seq Scan" : "Seq Scan",
            table,
            SqlIdent.quote(column),
            node.planRows(),
            reltuples < 0 ? "?" : Long.toString(reltuples),
            node.totalCost());
  }
}
