package com.pglens.engine.confirm;

import com.fasterxml.jackson.databind.JsonNode;
import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.TargetInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What {@code pglens confirm} will check, read from a {@code pglens scan --json} report (contract
 * 1.x): the first {@code top} distinct actionable indexes of its ranked list, in rank order, each
 * with every report query it was planner-validated for and that query's generic-plan estimate
 * (ADR-0042). The ranked list repeats an index once per query, so it is merged here — the same
 * one-line-per-index view the CLI prints. Pure — no I/O.
 */
public record ConfirmPlan(
    String scanGeneratedAt, TargetInfo scanTarget, List<IndexToConfirm> indexes) {

  public ConfirmPlan {
    indexes = indexes == null ? List.of() : List.copyOf(indexes);
  }

  /** A report query an index was validated for, with PgLens's estimate for it. */
  public record QueryEstimate(
      long queryId, String normalizedText, boolean truncated, Double estimatedDrop) {}

  /** One distinct recommended index, in the report's rank order (1-based). */
  public record IndexToConfirm(
      int rank, IndexCandidate candidate, List<QueryEstimate> queries, String buildCaution) {

    public IndexToConfirm {
      queries = queries == null ? List.of() : List.copyOf(queries);
    }

    /** "CREATE INDEX ON table [USING am ](cols);" — the report's index, without PgLens's name. */
    public String ddl() {
      return candidate.ddl().replaceFirst("^CREATE INDEX \\S+ ON ", "CREATE INDEX ON ");
    }
  }

  /**
   * Reads the plan from a parsed scan report. Throws {@link IllegalArgumentException} with a
   * user-facing message when the JSON isn't a PgLens scan report this version understands.
   */
  public static ConfirmPlan from(JsonNode report, int top) {
    String version = report.path("schemaVersion").asText("");
    if (!version.startsWith("1.")) {
      throw new IllegalArgumentException(
          "Not a PgLens scan report (expected schemaVersion 1.x, found '" + version + "').");
    }
    JsonNode t = report.path("target");
    TargetInfo target =
        new TargetInfo(
            text(t, "host"),
            t.hasNonNull("port") ? t.get("port").asInt() : null,
            text(t, "database"),
            text(t, "serverVersion"),
            List.of());

    Map<Long, JsonNode> queries = new LinkedHashMap<>();
    for (JsonNode q : report.path("queries")) {
      queries.put(q.path("queryId").asLong(), q);
    }

    Map<String, IndexCandidate> chosen = new LinkedHashMap<>();
    for (JsonNode ranked : report.path("topRecommendations")) {
      if (chosen.size() >= top) {
        break;
      }
      if (!ranked.path("actionable").asBoolean(true)) {
        continue; // covered by a more general recommended index
      }
      IndexCandidate c = candidate(ranked.path("recommendation").path("candidate"));
      chosen.putIfAbsent(key(c), c);
    }

    List<IndexToConfirm> out = new ArrayList<>();
    int rank = 0;
    for (Map.Entry<String, IndexCandidate> e : chosen.entrySet()) {
      rank++;
      List<QueryEstimate> estimates = new ArrayList<>();
      String caution = null;
      for (JsonNode q : queries.values()) {
        for (JsonNode rec : q.path("recommendations")) {
          JsonNode v = rec.path("validation");
          if (!"PLANNER_VALIDATED".equals(v.path("status").asText())
              || !key(candidate(rec.path("candidate"))).equals(e.getKey())) {
            continue;
          }
          estimates.add(
              new QueryEstimate(
                  q.path("queryId").asLong(),
                  q.path("normalizedText").asText(),
                  q.path("truncated").asBoolean(false),
                  v.hasNonNull("relativeDelta") ? v.get("relativeDelta").asDouble() : null));
          if (caution == null && v.hasNonNull("buildCaution")) {
            caution = v.get("buildCaution").asText();
          }
        }
      }
      out.add(new IndexToConfirm(rank, e.getValue(), estimates, caution));
    }
    return new ConfirmPlan(text(report, "generatedAt"), target, out);
  }

  private static IndexCandidate candidate(JsonNode c) {
    List<String> columns = new ArrayList<>();
    c.path("columns").forEach(col -> columns.add(col.asText()));
    AccessMethod method =
        AccessMethod.valueOf(c.path("accessMethod").asText("BTREE").toUpperCase(Locale.ROOT));
    return IndexCandidate.of(c.path("table").asText(), columns, method, List.of(), null);
  }

  private static String key(IndexCandidate c) {
    return (c.table() + "|" + String.join(",", c.columns()) + "|" + c.accessMethod())
        .toLowerCase(Locale.ROOT);
  }

  private static String text(JsonNode node, String field) {
    return node.hasNonNull(field) ? node.get(field).asText() : null;
  }
}
