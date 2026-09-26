package com.pglens.explain;

import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.Finding;
import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.QueryReport;
import com.pglens.engine.model.ValidationResult;
import com.pglens.explain.ExplanationFacts.Estimate;
import com.pglens.explain.ExplanationFacts.FindingFact;
import com.pglens.explain.ExplanationFacts.Index;
import com.pglens.explain.ExplanationFacts.Measured;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns an {@link ExplanationTarget} into {@link ExplanationFacts}: the query, its measured stats,
 * the index (table, columns, method), the planner estimate, and only the findings this index
 * addresses. Pure and deterministic — the same target always yields equal facts (the server cache
 * keys on their hash).
 */
public final class FactsBuilder {

  /** Keeps the prompt inside the ~2k-token budget (Ollama's OpenAI API context is 4,096). */
  static final int QUERY_MAX_CHARS = 1_200;

  private FactsBuilder() {}

  public static ExplanationFacts build(ExplanationTarget target) {
    QueryReport q = target.query();
    IndexCandidate c = target.recommendation().candidate();
    ValidationResult v = target.recommendation().validation();

    String text = q.normalizedText() == null ? "" : q.normalizedText().strip();
    boolean cut = text.length() > QUERY_MAX_CHARS;
    if (cut) {
      text = text.substring(0, QUERY_MAX_CHARS) + " …";
    }

    List<FindingFact> addressed = new ArrayList<>();
    Set<String> distinct = new LinkedHashSet<>();
    int others = 0;
    for (Finding f : q.findings()) {
      String key = f.ruleId() + '|' + f.table() + '|' + f.columns() + '|' + f.evidence();
      if (!distinct.add(key)) {
        continue; // the detector can report one scan node twice; say it once
      }
      if (addresses(c, f)) {
        addressed.add(new FindingFact(f.ruleId(), f.title(), f.table(), f.columns(), f.evidence()));
      } else {
        others++;
      }
    }

    boolean validated = v.status() == ValidationResult.Status.PLANNER_VALIDATED;
    Estimate estimate =
        validated && v.costBefore() != null && v.costAfter() != null && v.relativeDelta() != null
            ? new Estimate(
                Formats.cost(v.costBefore()),
                Formats.cost(v.costAfter()),
                Formats.percent(v.relativeDelta()))
            : null;
    String status =
        estimate != null
            ? "planner-validated (HypoPG estimate)"
            : "not planner-validated: " + v.label();

    return new ExplanationFacts(
        text,
        q.truncated() || cut,
        new Measured(
            Formats.count(q.calls()),
            Formats.duration(q.meanExecMs()),
            Formats.duration(q.totalExecMs())),
        new Index(c.table(), c.columns(), method(c.accessMethod())),
        status,
        estimate,
        addressed,
        others,
        target.alsoValidatedForOtherQueries());
  }

  /** Same table, a rule the candidate came from, and a key column of the index. */
  static boolean addresses(IndexCandidate c, Finding f) {
    if (!c.table().equalsIgnoreCase(f.table()) || !c.sourceRuleIds().contains(f.ruleId())) {
      return false;
    }
    return f.columns().stream()
        .anyMatch(col -> c.columns().stream().anyMatch(col::equalsIgnoreCase));
  }

  static String method(AccessMethod m) {
    return m == AccessMethod.BTREE ? "B-tree" : m.name().toUpperCase(Locale.ROOT);
  }
}
