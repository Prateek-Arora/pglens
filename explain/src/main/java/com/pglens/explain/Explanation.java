package com.pglens.explain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A plain-language explanation of one recommended index: the three prose fields, who wrote them,
 * and — when the LLM didn't — why not. The DDL, estimate label and caveats are not here; renderers
 * take those from the report itself (ADR-0043). Pure model.
 *
 * @param source {@code LLM} when the model's answer passed the guard, else {@code TEMPLATE}
 * @param model the model that wrote it ({@code null} for the template)
 * @param fallbackReason why the template was used instead of an LLM answer ({@code null} if not)
 * @param violations the guard rules the last rejected LLM answer broke (empty if none)
 * @param docs further-reading links from the cards used
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record Explanation(
    String ddl,
    Source source,
    String summary,
    String whyItIsSlow,
    String whatTheIndexChanges,
    String model,
    String fallbackReason,
    List<String> violations,
    List<String> docs) {

  public Explanation {
    violations = violations == null ? List.of() : List.copyOf(violations);
    docs = docs == null ? List.of() : List.copyOf(docs);
  }

  /** Who wrote the prose. */
  public enum Source {
    LLM,
    TEMPLATE
  }

  /** This explanation with these further-reading links. */
  public Explanation withDocs(List<String> links) {
    return new Explanation(
        ddl,
        source,
        summary,
        whyItIsSlow,
        whatTheIndexChanges,
        model,
        fallbackReason,
        violations,
        links);
  }

  /** This explanation with the fallback reason and violations that led to it. */
  public Explanation withFallback(String reason, List<String> brokenRules) {
    return new Explanation(
        ddl, source, summary, whyItIsSlow, whatTheIndexChanges, model, reason, brokenRules, docs);
  }
}
