package com.pglens.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pglens.explain.ExplanationFacts.FindingFact;
import com.pglens.explain.Quantities.Dim;
import com.pglens.explain.Quantities.Quantity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The trust layer (ADR-0043): an LLM answer is shown only if every check passes —
 *
 * <ol>
 *   <li><b>shape</b>: a JSON object with exactly the three prose fields, each non-empty and at most
 *       {@value #MAX_FIELD_CHARS} characters;
 *   <li><b>no SQL</b>: no statements, no {@code USING <method>}, no code fences;
 *   <li><b>numbers</b>: every number, read with its magnitude and unit, is within 5 % of a number
 *       of the same kind in the facts (bare 1–3 are allowed as ordinary counts);
 *   <li><b>identifiers</b>: every {@code snake_case}, {@code table.column} or backticked name is in
 *       the facts or the query text, and every index mentioned is the recommended one;
 *   <li><b>honesty</b>: the cost drop is called a cost or an estimate, never time or speed; no "N×
 *       faster", no promises; no percentage for an index the planner couldn't check.
 * </ol>
 *
 * What it can't catch — a wrong sentence with no number or name in it — the eval measures ({@code
 * docs/llm-eval.md}, M4) and the provenance line discloses. Pure.
 */
public final class OutputGuard {

  static final int MAX_FIELD_CHARS = 400;
  static final double NUMBER_TOLERANCE = 0.05;

  /** The three prose fields of an answer that parsed. */
  public record Prose(String summary, String whyItIsSlow, String whatTheIndexChanges) {
    String all() {
      return summary + "\n" + whyItIsSlow + "\n" + whatTheIndexChanges;
    }
  }

  /** The verdict: {@code prose} is set when the answer parsed, even if other checks failed. */
  public record Result(Prose prose, List<String> violations) {
    public boolean passed() {
      return prose != null && violations.isEmpty();
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Pattern SQL =
      Pattern.compile(
          "(?i)\\b(?:CREATE|DROP|ALTER)\\s+(?:UNIQUE\\s+)?(?:INDEX|TABLE)\\b"
              + "|(?i)\\bUSING\\s+(?:btree|gin|gist|hash|brin|bloom)\\b"
              + "|(?-i)\\b(?:INSERT\\s+INTO|DELETE\\s+FROM|UPDATE\\s+\\w+\\s+SET|VACUUM|REINDEX)\\b"
              + "|```");
  private static final Pattern DOTTED =
      Pattern.compile("(?<![\\w.])([A-Za-z_]\\w*)\\.([A-Za-z_]\\w*)(?![\\w(])");
  private static final Pattern SNAKE = Pattern.compile("(?<![\\w.])([A-Za-z]\\w*_\\w*)(?![\\w.])");
  private static final Pattern BACKTICKED = Pattern.compile("`([A-Za-z_][\\w.]*)`");
  private static final Pattern INDEX_ON =
      Pattern.compile(
          "(?i)\\bindex(?:es)?\\s+on\\s+(?:the\\s+)?[`\"']?([A-Za-z_]\\w*)(?:\\.([A-Za-z_]\\w*))?"
              + "[`\"']?(?:\\s+table)?(?:\\s*\\(([^)]*)\\))?");
  private static final Pattern CALL_SHAPE =
      Pattern.compile("(?<![\\w.])([A-Za-z_]\\w*)\\s*\\(\\s*([A-Za-z_][\\w\\s,]*)\\)");
  private static final Set<String> FUNCTIONS =
      Set.of(
          "date_part",
          "date_trunc",
          "to_char",
          "to_date",
          "array_agg",
          "string_agg",
          "jsonb_path_exists",
          "row_number");
  private static final Pattern RUNTIME_WORDS =
      Pattern.compile(
          "(?i)\\b(?:runtime|run time|execution time|response time|latency|faster|quicker"
              + "|speed(?:s|ed)?\\s+up|speed-?up)\\b");
  private static final Pattern COST_WORDS =
      Pattern.compile("(?i)\\b(?:cost|costs|estimat\\w*|planner)\\b");
  private static final Pattern MULTIPLIER_CLAIM =
      Pattern.compile(
          "(?i)\\b(?:times|x)\\s+(?:faster|quicker|slower|less|fewer|more)\\b"
              + "|\\b(?:twice|double|triple|half)\\s+as\\s+(?:fast|quick|slow)\\b");
  private static final Pattern PROMISE =
      Pattern.compile(
          "(?i)\\bguarantee\\w*|\\bwill\\s+(?:fix|solve|eliminate|resolve)\\b"
              + "|\\bwill\\s+(?:be|run|become)\\s+(?:fast|faster|instant\\w*)\\b|\\binstantly\\b");
  private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?])\\s+");

  private OutputGuard() {}

  public static Result check(String content, ExplanationFacts facts) {
    List<String> violations = new ArrayList<>();
    Prose prose = parse(content, violations);
    if (prose == null) {
      return new Result(null, violations);
    }
    Set<String> found = new LinkedHashSet<>();
    String text = prose.all();
    sql(text, found);
    numbers(text, facts, found);
    identifiers(text, facts, found);
    honesty(text, facts, found);
    violations.addAll(found);
    return new Result(prose, violations);
  }

  // --- 1. shape ---------------------------------------------------------------------------------

  static Prose parse(String content, List<String> violations) {
    String s = content == null ? "" : content.strip();
    int start = s.indexOf('{');
    int end = s.lastIndexOf('}');
    if (start < 0 || end < start) {
      violations.add("shape: the answer must be one JSON object");
      return null;
    }
    JsonNode node;
    try {
      node = JSON.readTree(s.substring(start, end + 1));
    } catch (Exception e) {
      violations.add("shape: the answer must be valid JSON");
      return null;
    }
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, JsonNode> field : node.properties()) {
      String name = field.getKey();
      if (!PromptBuilder.FIELDS.contains(name)) {
        problems.add("shape: unexpected field \"" + name + "\"");
      }
    }
    String[] values = new String[3];
    for (int i = 0; i < 3; i++) {
      String field = PromptBuilder.FIELDS.get(i);
      JsonNode v = node.get(field);
      if (v == null || !v.isTextual() || v.asText().isBlank()) {
        problems.add("shape: \"" + field + "\" must be a non-empty string");
      } else if (v.asText().length() > MAX_FIELD_CHARS) {
        problems.add("shape: \"" + field + "\" is longer than " + MAX_FIELD_CHARS + " characters");
      }
      values[i] = v == null ? "" : v.asText().strip();
    }
    violations.addAll(problems);
    boolean unusable = problems.stream().anyMatch(p -> p.contains("non-empty"));
    return unusable ? null : new Prose(values[0], values[1], values[2]);
  }

  // --- 2. no SQL --------------------------------------------------------------------------------

  private static void sql(String text, Set<String> found) {
    Matcher m = SQL.matcher(text);
    if (m.find()) {
      found.add(
          "sql: don't write SQL or code (found \""
              + m.group().strip()
              + "\"); PgLens shows the index itself");
    }
  }

  // --- 3. numbers -------------------------------------------------------------------------------

  private static void numbers(String text, ExplanationFacts facts, Set<String> found) {
    List<Quantity> known = Quantities.scan(factsText(facts));
    for (Quantity q : Quantities.scan(text)) {
      if (q.dim() == Dim.MULTIPLIER) {
        found.add("honesty: no \"N× faster\" claims (found \"" + q.raw() + "\")");
        continue;
      }
      if (q.dim() == Dim.COUNT
          && q.value() >= 0
          && q.value() <= 3
          && q.value() == Math.rint(q.value())) {
        continue; // "one index", "2 columns"
      }
      if (!Quantities.supported(q, known, NUMBER_TOLERANCE)) {
        found.add("number: \"" + q.raw() + "\" is not in FACTS");
      }
    }
  }

  static String factsText(ExplanationFacts facts) {
    try {
      return PromptBuilder.JSON.writeValueAsString(facts);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // --- 4. identifiers and index mentions
  // ----------------------------------------------------------

  private static void identifiers(String text, ExplanationFacts facts, Set<String> found) {
    Set<String> known = knownIdentifiers(facts);
    String table = facts.index().table().toLowerCase(Locale.ROOT);
    Set<String> columns = lower(facts.index().columns());

    Matcher dotted = DOTTED.matcher(text);
    while (dotted.find()) {
      String left = dotted.group(1).toLowerCase(Locale.ROOT);
      String right = dotted.group(2).toLowerCase(Locale.ROOT);
      if (left.length() == 1 && right.length() == 1) {
        continue; // "e.g", "i.e"
      }
      if (!known.contains(left) || !known.contains(right)) {
        found.add("identifier: \"" + dotted.group() + "\" is not a table/column in FACTS");
      }
    }
    for (Pattern p : List.of(SNAKE, BACKTICKED)) {
      Matcher m = p.matcher(text);
      while (m.find()) {
        String name = m.group(1).toLowerCase(Locale.ROOT).replaceAll("[^\\w.]", "");
        for (String part : name.split("\\.")) {
          if (!part.isEmpty() && !known.contains(part) && !part.matches("\\d+")) {
            found.add("identifier: \"" + part + "\" is not a table/column in FACTS");
          }
        }
      }
    }

    Set<String> schema = schemaNames(facts);
    String recommended =
        "only the recommended index on "
            + facts.index().table()
            + " ("
            + String.join(", ", facts.index().columns())
            + ") may be mentioned";
    Matcher on = INDEX_ON.matcher(text);
    while (on.find()) {
      String first = on.group(1).toLowerCase(Locale.ROOT);
      List<String> cols = new ArrayList<>();
      if (on.group(2) != null) {
        cols.add(on.group(2).toLowerCase(Locale.ROOT));
      }
      if (on.group(3) != null) {
        cols.addAll(columnList(on.group(3)));
      }
      boolean onTable = on.group(2) != null || !cols.isEmpty() || first.equals(table);
      boolean ok =
          onTable
              ? first.equals(table) && columns.containsAll(cols)
              : columns.contains(first) || !schema.contains(first); // "an index on it" is English
      if (!ok) {
        found.add(
            "index: mentions an index on \""
                + on.group(1)
                + (on.group(2) == null ? "" : "." + on.group(2))
                + "\"; "
                + recommended);
      }
    }

    Matcher call = CALL_SHAPE.matcher(text);
    while (call.find()) {
      String name = call.group(1).toLowerCase(Locale.ROOT);
      boolean tableLike =
          (name.contains("_") && !FUNCTIONS.contains(name)) || factTables(facts).contains(name);
      if (!tableLike) {
        continue;
      }
      List<String> cols = columnList(call.group(2));
      if (!cols.isEmpty() && (!name.equals(table) || !columns.containsAll(cols))) {
        found.add("index: mentions \"" + call.group().strip() + "\"; " + recommended);
      }
    }
  }

  private static Set<String> knownIdentifiers(ExplanationFacts facts) {
    Set<String> known = new HashSet<>(factTables(facts));
    known.addAll(lower(facts.index().columns()));
    for (FindingFact f : facts.findings()) {
      known.addAll(lower(f.columns()));
    }
    Matcher words =
        Pattern.compile("[A-Za-z_]\\w*").matcher(facts.query() == null ? "" : facts.query());
    while (words.find()) {
      known.add(words.group().toLowerCase(Locale.ROOT));
    }
    return known;
  }

  private static Set<String> factTables(ExplanationFacts facts) {
    Set<String> tables = new HashSet<>();
    tables.add(facts.index().table().toLowerCase(Locale.ROOT));
    facts.findings().forEach(f -> tables.add(f.table().toLowerCase(Locale.ROOT)));
    return tables;
  }

  /**
   * "status, created_at DESC" → [status, created_at]; empty when the parentheses hold prose ("the
   * join key") rather than a column list, which is then not an index mention.
   */
  private static List<String> columnList(String list) {
    List<String> out = new ArrayList<>();
    for (String c : list.split(",")) {
      String[] words = c.strip().replaceAll("[`\"']", "").toLowerCase(Locale.ROOT).split("\\s+");
      boolean column = words.length == 1 || (words.length == 2 && words[1].matches("asc|desc"));
      if (!column || !words[0].matches("[a-z_]\\w*")) {
        return List.of();
      }
      out.add(words[0]);
    }
    return out;
  }

  /** Table and column names only — the query's SQL keywords (JOIN, WHERE) aren't schema. */
  private static Set<String> schemaNames(ExplanationFacts facts) {
    Set<String> names = new HashSet<>(factTables(facts));
    names.addAll(lower(facts.index().columns()));
    facts.findings().forEach(f -> names.addAll(lower(f.columns())));
    Matcher words = SNAKE.matcher(facts.query() == null ? "" : facts.query());
    while (words.find()) {
      names.add(words.group(1).toLowerCase(Locale.ROOT));
    }
    return names;
  }

  private static Set<String> lower(List<String> names) {
    Set<String> out = new HashSet<>();
    names.forEach(n -> out.add(n.toLowerCase(Locale.ROOT)));
    return out;
  }

  // --- 5. honesty --------------------------------------------------------------------------------

  private static void honesty(String text, ExplanationFacts facts, Set<String> found) {
    Matcher mult = MULTIPLIER_CLAIM.matcher(text);
    if (mult.find()) {
      found.add("honesty: no \"N× faster\" claims (found \"" + mult.group() + "\")");
    }
    Matcher promise = PROMISE.matcher(text);
    if (promise.find()) {
      found.add("honesty: don't promise results (found \"" + promise.group() + "\")");
    }
    if (!facts.plannerValidated()) {
      for (Quantity q : Quantities.scan(text)) {
        if (q.dim() == Dim.PERCENT) {
          found.add(
              "honesty: this index is not planner-validated, so give no percentage (found \""
                  + q.raw()
                  + "\")");
          break;
        }
      }
      return;
    }
    double drop = Double.parseDouble(facts.plannerEstimate().costDrop().replace("%", ""));
    for (String sentence : SENTENCE.split(text.replace('\n', ' '))) {
      boolean carriesDrop =
          Quantities.scan(sentence).stream()
              .anyMatch(q -> q.dim() == Dim.PERCENT && Math.abs(q.value() - drop) <= 0.6);
      if (!carriesDrop) {
        continue;
      }
      if (RUNTIME_WORDS.matcher(sentence).find() || !COST_WORDS.matcher(sentence).find()) {
        found.add(
            "honesty: the "
                + facts.plannerEstimate().costDrop()
                + " is a drop in the planner's estimated cost; call it that, not time or speed");
      }
    }
  }
}
