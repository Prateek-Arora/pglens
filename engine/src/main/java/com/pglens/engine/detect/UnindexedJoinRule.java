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
 * R3 — an equi-join whose key is unindexed on a side, forcing that side to be scanned. Reads the
 * join condition ({@code a.x = b.y}) and flags each side whose column no index leads with (so a PK
 * or already-indexed side is not flagged). MVP scope: hash-join conditions (the common seq-scan
 * case in the demo); merge/nested-loop conditions on other node fields are a later refinement.
 */
final class UnindexedJoinRule implements Rule {

  @Override
  public String id() {
    return "R3";
  }

  @Override
  public List<Finding> evaluate(PlanContext ctx) {
    List<Finding> findings = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>(); // dedupe table+column across joins
    for (PlanNode node : ctx.plan.flatten()) {
      if (!node.isJoin() || node.hashCond() == null) {
        continue;
      }
      for (QualifiedColumn qc : PlanColumns.joinColumns(node.hashCond())) {
        String table = ctx.resolveTable(qc.qualifier());
        if (table == null || ctx.catalog.hasIndexLeadingWith(table, qc.column())) {
          continue; // unknown, or already indexed (e.g. the PK side)
        }
        if (!seen.add(table.toLowerCase() + "." + qc.column().toLowerCase())) {
          continue;
        }
        long reltuples = ctx.catalog.reltuples(table);
        findings.add(
            new Finding(
                id(),
                "Unindexed join key",
                table,
                List.of(qc.column()),
                reltuples >= DetectionThresholds.LARGE_TABLE ? Confidence.HIGH : Confidence.MEDIUM,
                "Join on %s.%s has no index, so the %s side is scanned (~%s rows)."
                    .formatted(
                        table,
                        qc.column(),
                        table,
                        reltuples < 0 ? "?" : Long.toString(reltuples))));
      }
    }
    return findings;
  }
}
