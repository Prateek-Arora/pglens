package com.pglens.explain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pglens.explain.KnowledgeCards.Card;
import com.pglens.explain.ReferenceSource.Reference;
import com.pglens.explain.llm.OpenAiCompatibleClient.ChatRequest;
import java.util.List;

/**
 * Builds the chat request for one explanation: a fixed system prompt, the facts as JSON, the cards,
 * any retrieved docs, and — on the retry — the rules the first answer broke. {@link #VERSION} is
 * part of the server's cache key: bump it whenever the prompt's wording or inputs change.
 */
public final class PromptBuilder {

  /** Bump on any change to the prompt, the schema or the facts shape (server cache key). */
  public static final String VERSION = "p1";

  static final String SCHEMA_NAME = "explanation";
  static final List<String> FIELDS = List.of("summary", "whyItIsSlow", "whatTheIndexChanges");

  static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .defaultPropertyInclusion(
              JsonInclude.Value.construct(
                  JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
          .build();

  static final String SYSTEM =
      """
      You explain one PostgreSQL index recommendation to a developer, in plain language.
      Use ONLY the FACTS and the REFERENCE. Answer with a JSON object with three string fields:
      - summary: what PgLens recommends and, if FACTS has a plannerEstimate, what the planner estimates.
      - whyItIsSlow: what the current plan does that is slow — only for the findings in FACTS.
      - whatTheIndexChanges: how this index changes that plan.
      Rules:
      - 1 to 3 short sentences per field. No lists, no markdown.
      - Don't write SQL or an index definition; PgLens shows the index itself.
      - Only use numbers that appear in FACTS, written the same way or rounded.
      - Costs are planner estimates: call them cost or estimated cost, never time or speed. Never
        promise a speed-up and never say how many times faster anything gets.
      - Mention no index other than the one in FACTS, and no table or column that isn't in FACTS.
      - The query may have other problems (otherFindingsInQuery); don't say this index fixes them.
      - If status says "not planner-validated", give no estimate of the benefit.
      """;

  private PromptBuilder() {}

  public static ChatRequest build(
      ExplanationFacts facts, List<Reference> references, List<String> previousViolations) {
    StringBuilder user = new StringBuilder("FACTS (authoritative):\n");
    try {
      user.append(JSON.writeValueAsString(facts));
    } catch (Exception e) {
      throw new IllegalStateException("facts not serializable", e);
    }
    user.append("\n\nREFERENCE:\n");
    for (Card card : KnowledgeCards.forFacts(facts)) {
      user.append("- ").append(card.title()).append(": ").append(card.body()).append('\n');
    }
    for (Reference ref : references) {
      user.append("- ").append(ref.title()).append(": ").append(ref.text()).append('\n');
    }
    if (!previousViolations.isEmpty()) {
      user.append("\nYour previous answer broke these rules; write it again without them:\n");
      previousViolations.forEach(v -> user.append("- ").append(v).append('\n'));
    }
    return new ChatRequest(SYSTEM, user.toString().strip(), SCHEMA_NAME, schema());
  }

  static JsonNode schema() {
    ObjectNode schema = JSON.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    ObjectNode props = schema.putObject("properties");
    FIELDS.forEach(f -> props.putObject(f).put("type", "string"));
    FIELDS.forEach(schema.putArray("required")::add);
    return schema;
  }
}
