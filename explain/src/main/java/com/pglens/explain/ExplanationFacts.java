package com.pglens.explain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * Everything an explanation may say, and nothing else: the facts the LLM sees (serialized as JSON
 * into the prompt), the template reads, and the guard checks against. Built by {@link FactsBuilder}
 * from the engine model. Numbers are pre-formatted strings (see {@link Formats}).
 *
 * <p>Deliberately absent (ADR-0043): the index DDL and every caveat — value range, size, write
 * load, build caution, "planner-validated ≠ safe" — which PgLens renders itself; and anything from
 * the user's data (sampled values never leave the engine, ADR-0038). Pure model.
 */
@JsonPropertyOrder({
  "query",
  "queryTruncated",
  "measured",
  "index",
  "status",
  "plannerEstimate",
  "findings",
  "otherFindingsInQuery",
  "alsoValidatedForOtherQueries"
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExplanationFacts(
    String query,
    boolean queryTruncated,
    Measured measured,
    Index index,
    String status,
    Estimate plannerEstimate,
    List<FindingFact> findings,
    int otherFindingsInQuery,
    int alsoValidatedForOtherQueries) {

  public ExplanationFacts {
    findings = findings == null ? List.of() : List.copyOf(findings);
  }

  /** Real {@code pg_stat_statements} numbers for the query. */
  public record Measured(String calls, String meanTime, String totalTime) {}

  /** The recommended index, without its DDL (PgLens prints that verbatim). */
  public record Index(String table, List<String> columns, String method) {
    public Index {
      columns = List.copyOf(columns);
    }
  }

  /** HypoPG's generic-plan cost estimate — absent when the index couldn't be planner-checked. */
  public record Estimate(String costBefore, String costAfter, String costDrop) {}

  /** A finding this index addresses (same rule, table, and a key column). */
  public record FindingFact(
      String rule, String title, String table, List<String> columns, String evidence) {
    public FindingFact {
      columns = List.copyOf(columns);
    }
  }

  /** True when HypoPG checked the index against this query's plan. */
  public boolean plannerValidated() {
    return plannerEstimate != null;
  }
}
