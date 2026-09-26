package com.pglens.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pglens.engine.db.HypoPGValidator;
import com.pglens.engine.model.TableWriteLoad;
import com.pglens.engine.model.ValidationResult;
import com.pglens.engine.model.ValueRangeEstimate;
import com.pglens.explain.Explanation;
import com.pglens.explain.Explanation.Source;
import com.pglens.explain.ExplanationTarget;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders {@code --plain} explanations. The prose is the explainer's; everything with a number or a
 * warning in it — the DDL, the estimate label, value-range and build cautions, write load, the
 * "planner-validated ≠ safe" note — is rendered here from the report itself, never by the model
 * (ADR-0043).
 */
final class ExplanationRenderer {

  static final String NOT_SAFE_NOTE =
      "Planner-validated means the planner estimates less work, not that the query will run faster."
          + " Try an index on a copy first: pglens confirm.";

  private ExplanationRenderer() {}

  static String toHuman(
      List<ExplanationTarget> targets, List<Explanation> explanations, List<TableWriteLoad> loads) {
    StringBuilder sb = new StringBuilder("\nPlain-language explanations\n");
    if (explanations.isEmpty()) {
      return sb.append("  (no recommended index to explain)\n").toString();
    }
    Map<String, TableWriteLoad> byTable =
        loads.stream().collect(Collectors.toMap(TableWriteLoad::table, w -> w, (a, b) -> a));
    boolean anyValidated = false;
    for (int i = 0; i < explanations.size(); i++) {
      Explanation e = explanations.get(i);
      ValidationResult v = targets.get(i).recommendation().validation();
      anyValidated |= v.isRecommended();
      sb.append('\n').append(i + 1).append(". ").append(e.ddl()).append('\n');
      field(sb, "In short", e.summary());
      field(sb, "Why it's slow", e.whyItIsSlow());
      field(sb, "What the index changes", e.whatTheIndexChanges());
      sb.append("   Estimate: ").append(v.label()).append('\n');
      ValueRangeEstimate range = v.valueRange();
      if (range != null
          && range.worstRelativeDrop() < HypoPGValidator.DEFAULT_MIN_RELATIVE_IMPROVEMENT) {
        sb.append("   ⚠ ").append(range.label()).append('\n');
      }
      if (v.buildCaution() != null) {
        sb.append("   ⚠ ").append(v.buildCaution()).append('\n');
      }
      TableWriteLoad load = byTable.get(targets.get(i).recommendation().candidate().table());
      if (load != null && load.level() == TableWriteLoad.Level.WRITE_DOMINANT) {
        sb.append("   ⚠ ").append(load.label()).append('\n');
      }
      sb.append("   ").append(provenance(e)).append('\n');
      if (!e.docs().isEmpty()) {
        sb.append("   Read more: ")
            .append(String.join("  ", e.docs().subList(0, Math.min(2, e.docs().size()))))
            .append('\n');
      }
    }
    if (anyValidated) {
      sb.append('\n').append(NOT_SAFE_NOTE).append('\n');
    }
    return sb.toString();
  }

  /** Where the prose came from — and, for the model, what was and wasn't checked. */
  static String provenance(Explanation e) {
    if (e.source() == Source.LLM) {
      return "(Written by "
          + e.model()
          + " from PgLens's findings. Numbers, tables and the index"
          + " were checked against them; the wording wasn't.)";
    }
    if (e.fallbackReason() == null) {
      return "(PgLens's template.)";
    }
    return "(PgLens's template — the model's answer wasn't used: " + e.fallbackReason() + ".)";
  }

  /** The {@code --json} 1.3 report: the scan report plus a root {@code explanations} array. */
  static String withExplanations(String reportJson, List<Explanation> explanations) {
    try {
      ObjectMapper json = new ObjectMapper();
      ObjectNode root = (ObjectNode) json.readTree(reportJson);
      root.set("explanations", json.valueToTree(explanations));
      return json.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to render JSON explanations", e);
    }
  }

  private static void field(StringBuilder sb, String label, String text) {
    sb.append("   ").append(label).append(": ").append(text).append('\n');
  }
}
