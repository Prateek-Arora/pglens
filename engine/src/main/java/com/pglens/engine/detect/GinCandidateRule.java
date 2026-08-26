package com.pglens.engine.detect;

import com.pglens.engine.detect.PlanColumns.JsonbPredicate;
import com.pglens.engine.model.Finding;
import com.pglens.engine.model.Finding.Confidence;
import com.pglens.engine.model.PlanNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R7 — a sequential scan applying a containment/existence filter (jsonb {@code @>}, key-exists
 * {@code ?}/{@code ?|}/{@code ?&}, or jsonpath/full-text {@code @?}/{@code @@}) on a column no
 * index leads with, on a table big enough to matter. These operators are served by a <b>GIN</b>
 * index — which HypoPG <em>cannot</em> simulate — so the candidate is surfaced but routed to the
 * <em>not planner-validated</em> bucket downstream (never dropped, never given a fabricated cost
 * delta). Scalar/btree predicates ({@code =}, ranges) are R1's job; this rule owns only the
 * GIN-servable operators.
 *
 * <p>MVP scope: LIKE {@code '%…%'} (trigram GIN via {@code pg_trgm}) is deliberately not detected
 * here — there is no demo fixture for it and it needs a non-default extension/opclass; it is a
 * later addition (see ADR-0019).
 */
final class GinCandidateRule implements Rule {

  @Override
  public String id() {
    return "R7";
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

      Set<String> flagged = new LinkedHashSet<>(); // one finding per column per scan
      for (JsonbPredicate jp : PlanColumns.jsonbPredicates(node.filter())) {
        String qualifier = jp.column().qualifier();
        String table = qualifier == null ? node.relationName() : ctx.resolveTable(qualifier);
        String column = jp.column().column();
        if (table != null
            && table.equalsIgnoreCase(node.relationName())
            && !ctx.catalog.hasIndexLeadingWith(table, column)
            && flagged.add(column)) {
          findings.add(
              new Finding(
                  id(),
                  "Unindexed containment/existence filter (GIN candidate)",
                  table,
                  List.of(column),
                  Confidence.MEDIUM,
                  evidence(node.relationName(), column, jp.operator(), reltuples)));
        }
      }
    }
    return findings;
  }

  private static String evidence(String table, String column, String operator, long reltuples) {
    return ("Seq Scan on %s filters %s with a %s operator over ~%s rows; a GIN index can serve "
            + "containment/existence lookups. Not planner-validated — HypoPG cannot simulate GIN.")
        .formatted(table, column, operator, reltuples < 0 ? "?" : Long.toString(reltuples));
  }
}
