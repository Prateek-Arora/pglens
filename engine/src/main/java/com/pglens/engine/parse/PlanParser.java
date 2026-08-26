package com.pglens.engine.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pglens.engine.model.PlanNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code EXPLAIN (FORMAT JSON)} output into a typed {@link PlanNode} tree.
 *
 * <p>Pure — no Spring, no I/O. Jackson is the only dependency (allowed in pure packages).
 */
public final class PlanParser {

  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * Parses the root plan node out of EXPLAIN JSON (a one-element array wrapping a {@code Plan}).
   */
  public PlanNode parse(String explainJson) {
    final JsonNode root;
    try {
      root = mapper.readTree(explainJson);
    } catch (Exception e) {
      throw new IllegalArgumentException("Not valid EXPLAIN JSON", e);
    }
    JsonNode top = root != null && root.isArray() ? root.get(0) : root;
    if (top == null || !top.has("Plan")) {
      throw new IllegalArgumentException("EXPLAIN JSON has no \"Plan\" element");
    }
    return toNode(top.get("Plan"));
  }

  private PlanNode toNode(JsonNode n) {
    List<PlanNode> children = new ArrayList<>();
    JsonNode plans = n.get("Plans");
    if (plans != null && plans.isArray()) {
      for (JsonNode child : plans) {
        children.add(toNode(child));
      }
    }
    return new PlanNode(
        text(n, "Node Type"),
        bool(n, "Parallel Aware"),
        dbl(n, "Startup Cost"),
        dbl(n, "Total Cost"),
        lng(n, "Plan Rows"),
        (int) lng(n, "Plan Width"),
        text(n, "Relation Name"),
        text(n, "Alias"),
        text(n, "Index Name"),
        text(n, "Join Type"),
        text(n, "Filter"),
        text(n, "Index Cond"),
        text(n, "Recheck Cond"),
        text(n, "Hash Cond"),
        stringList(n, "Sort Key"),
        stringList(n, "Output"),
        n.hasNonNull("Workers Planned") ? n.get("Workers Planned").asInt() : null,
        n.hasNonNull("Actual Rows") ? n.get("Actual Rows").asLong() : null,
        n.hasNonNull("Rows Removed by Filter") ? n.get("Rows Removed by Filter").asLong() : null,
        text(n, "Sort Method"),
        children);
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }

  private static boolean bool(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v != null && v.asBoolean();
  }

  private static double dbl(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null ? 0.0 : v.asDouble();
  }

  private static long lng(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null ? 0L : v.asLong();
  }

  private static List<String> stringList(JsonNode n, String field) {
    JsonNode v = n.get(field);
    if (v == null || !v.isArray()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (JsonNode e : v) {
      out.add(e.asText());
    }
    return out;
  }
}
