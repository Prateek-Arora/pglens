package com.pglens.engine.detect;

import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.Finding;
import com.pglens.engine.model.PlanNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the anti-pattern rule set over a plan + catalog and returns every finding. Pure — no I/O, no
 * Spring — so it is fixture-testable and reusable server-side in Phase 2.
 *
 * <p>Rules are deliberately liberal: they flag patterns, and HypoPG validation downstream is the
 * decisive gate that turns a finding into (or suppresses) a recommendation. The default rule set is
 * R1 (selective unindexed filter), R3 (unindexed join key), R4 (sort feeding a Limit), and R7
 * (containment/existence filter → GIN candidate, surfaced but not planner-validated). Composite
 * indexes emerge later by merging findings during candidate generation; the ANALYZE-gated
 * mis-estimation rule and the advisory over-fetch rule are intentionally not wired yet.
 */
public final class AntiPatternDetector {

  private final List<Rule> rules;

  public AntiPatternDetector() {
    this(
        List.of(
            new SelectiveSeqScanRule(),
            new UnindexedJoinRule(),
            new SortLimitRule(),
            new GinCandidateRule()));
  }

  AntiPatternDetector(List<Rule> rules) {
    this.rules = List.copyOf(rules);
  }

  /** Every finding across all rules, in rule order. */
  public List<Finding> detect(PlanNode plan, CatalogSnapshot catalog) {
    if (plan == null) {
      return List.of();
    }
    PlanContext ctx = new PlanContext(plan, catalog == null ? CatalogSnapshot.empty() : catalog);
    List<Finding> all = new ArrayList<>();
    for (Rule rule : rules) {
      all.addAll(rule.evaluate(ctx));
    }
    return all;
  }
}
