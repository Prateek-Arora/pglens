package com.pglens.explain;

import com.pglens.explain.Explanation.Source;
import com.pglens.explain.ExplanationFacts.FindingFact;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The deterministic explanation: fixed sentences per rule, filled from the facts. Always available
 * — it is what {@code --plain} shows with the LLM off, unreachable, or failing the guard (charter
 * #3) — and the baseline the LLM must beat in the eval ({@code docs/llm-eval.md}). Pure.
 */
public final class TemplateExplainer {

  private TemplateExplainer() {}

  public static Explanation explain(ExplanationFacts facts, String ddl) {
    return new Explanation(
        ddl,
        Source.TEMPLATE,
        summary(facts),
        whyItIsSlow(facts),
        whatTheIndexChanges(facts),
        null,
        null,
        List.of(),
        docs(facts));
  }

  static String summary(ExplanationFacts f) {
    String index = indexPhrase(f);
    StringBuilder sb = new StringBuilder();
    if (f.plannerValidated()) {
      sb.append("The planner estimates that ")
          .append(index)
          .append(" cuts this query's cost by ")
          .append(f.plannerEstimate().costDrop())
          .append(" (")
          .append(f.plannerEstimate().costBefore())
          .append(" → ")
          .append(f.plannerEstimate().costAfter())
          .append(").");
    } else {
      sb.append(capitalize(index))
          .append(
              " could serve this query's filter, but PgLens could not check it with the planner.");
    }
    if ("1".equals(f.measured().calls())) {
      sb.append(" The query ran once, taking ").append(f.measured().totalTime()).append('.');
    } else {
      sb.append(" The query ran ")
          .append(f.measured().calls())
          .append(" times, taking ")
          .append(f.measured().meanTime())
          .append(" on average (")
          .append(f.measured().totalTime())
          .append(" in total).");
    }
    if (f.alsoValidatedForOtherQueries() > 0) {
      sb.append(" The same index also passed the planner check for ")
          .append(f.alsoValidatedForOtherQueries())
          .append(f.alsoValidatedForOtherQueries() == 1 ? " other query." : " other queries.");
    }
    return sb.toString();
  }

  static String whyItIsSlow(ExplanationFacts f) {
    if (f.findings().isEmpty()) {
      return "PgLens's plan checks point at " + f.index().table() + " for this query.";
    }
    List<String> sentences = new ArrayList<>();
    for (FindingFact finding : f.findings()) {
      String col = String.join(", ", finding.columns());
      String t = finding.table();
      sentences.add(
          switch (finding.rule()) {
            case "R1" ->
                "Postgres reads every row of "
                    + t
                    + " and keeps only those passing the filter on "
                    + col
                    + ".";
            case "R3" ->
                "The join looks up rows of "
                    + t
                    + " by "
                    + col
                    + ", which has no index, so Postgres scans "
                    + t
                    + " instead.";
            case "R4" ->
                "Postgres sorts the rows of " + t + " by " + col + " only to return the first few.";
            case "R7" ->
                "The containment filter on "
                    + col
                    + " makes Postgres check every row of "
                    + t
                    + ".";
            default -> finding.title() + ".";
          });
    }
    String evidence =
        f.findings().stream()
            .map(x -> readableNumbers(x.evidence()))
            .collect(Collectors.joining(" "));
    return String.join(" ", sentences) + " PgLens saw: " + evidence;
  }

  // A number of 4+ integer digits, not part of an identifier: "36236964", "479201.03".
  private static final Pattern BIG_NUMBER =
      Pattern.compile("(?<![\\w.,$])(\\d{4,})(\\.\\d+)?(?![\\w.])");

  /**
   * The engine's evidence prints raw numbers ("~36236964 rows, total cost 479201.03"); the template
   * shows them grouped and rounded ("~36,236,964 rows, total cost 479,201") — the same values, so
   * the guard's unit-aware match still holds.
   */
  static String readableNumbers(String evidence) {
    Matcher m = BIG_NUMBER.matcher(evidence);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      long n = Math.round(Double.parseDouble(m.group(1) + (m.group(2) == null ? "" : m.group(2))));
      m.appendReplacement(sb, Matcher.quoteReplacement(Formats.count(n)));
    }
    return m.appendTail(sb).toString();
  }

  static String whatTheIndexChanges(ExplanationFacts f) {
    Set<String> rules = f.findings().stream().map(FindingFact::rule).collect(Collectors.toSet());
    String index = "With " + indexPhrase(f) + ", Postgres ";
    List<String> cols = f.index().columns();
    if (rules.contains("R1") && rules.contains("R4") && cols.size() >= 2) {
      return index
          + "can go straight to the rows matching "
          + cols.get(0)
          + ", already in "
          + cols.get(cols.size() - 1)
          + " order, and stop after the first few.";
    }
    if (rules.contains("R7")) {
      return capitalize(indexPhrase(f))
          + " can answer containment lookups directly instead of checking every row.";
    }
    if (rules.contains("R4")) {
      return index
          + "can read rows already in "
          + String.join(", ", cols)
          + " order and stop after the first few.";
    }
    if (rules.contains("R3")) {
      return index
          + "can look up only the matching "
          + f.index().table()
          + " rows for each join key instead of scanning the table.";
    }
    return index + "can go straight to the matching rows instead of reading the whole table.";
  }

  private static List<String> docs(ExplanationFacts f) {
    return KnowledgeCards.forFacts(f).stream().flatMap(c -> c.docs().stream()).distinct().toList();
  }

  /** "an index on orders (customer_id)" / "a GIN index on events (payload)". */
  static String indexPhrase(ExplanationFacts f) {
    String on = " on " + f.index().table() + " (" + String.join(", ", f.index().columns()) + ")";
    return "B-tree".equals(f.index().method())
        ? "an index" + on
        : "a " + f.index().method() + " index" + on;
  }

  private static String capitalize(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
