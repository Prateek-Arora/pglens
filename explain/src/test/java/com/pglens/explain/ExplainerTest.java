package com.pglens.explain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pglens.explain.Explanation.Source;
import com.pglens.explain.llm.LlmSettings;
import com.pglens.explain.llm.OpenAiCompatibleClient;
import com.pglens.explain.llm.StubLlmServer;
import java.net.ServerSocket;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The retry-once-then-template flow, against a scripted endpoint (no model in CI). */
class ExplainerTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String GOOD =
      json(
          "The planner estimates the index on orders (customer_id) cuts this query's cost by 98.7%.",
          "Postgres reads all ~200000 orders and keeps the few for one customer.",
          "With the index, Postgres jumps straight to that customer's rows.");
  private static final String BAD =
      json(
          "This index makes the query 10× faster.",
          "Postgres reads all orders.",
          "CREATE INDEX ON orders (customer_id);");

  private StubLlmServer stub;
  private Explainer explainer;

  @BeforeEach
  void start() throws Exception {
    stub = new StubLlmServer();
    explainer =
        new Explainer(
            new OpenAiCompatibleClient(LlmSettings.defaults().withBaseUrl(stub.baseUrl())),
            ReferenceSource.NONE);
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  @Test
  void aCleanAnswerIsShownAsTheModelsWords() {
    stub.replyContent(GOOD);

    Explainer.Trace t = explainer.trace(EvalCases.byId("02-").target());

    assertThat(t.explanation().source()).isEqualTo(Source.LLM);
    assertThat(t.explanation().model()).isEqualTo("qwen3.5:4b");
    assertThat(t.explanation().summary()).contains("98.7%");
    assertThat(t.explanation().ddl())
        .isEqualTo("CREATE INDEX idx_orders_customer_id ON orders (customer_id);");
    assertThat(t.explanation().docs()).isNotEmpty();
    assertThat(t.attempts()).hasSize(1);
    assertThat(t.attempts().get(0).promptTokens()).isEqualTo(700);
  }

  @Test
  void aBadAnswerIsRetriedWithTheRulesItBroke() throws Exception {
    stub.replyContent(BAD).replyContent(GOOD);

    Explainer.Trace t = explainer.trace(EvalCases.byId("02-").target());

    assertThat(t.explanation().source()).isEqualTo(Source.LLM);
    assertThat(t.attempts()).hasSize(2);
    String retryPrompt = JSON.readTree(stub.requests().get(1)).at("/messages/1/content").asText();
    assertThat(retryPrompt)
        .contains("Your previous answer broke these rules")
        .contains("sql:")
        .contains("\"10×\"");
  }

  @Test
  void twoBadAnswersFallBackToTheTemplateWithTheViolations() {
    stub.replyContent(BAD).replyContent(BAD);

    Explanation e = explainer.explain(EvalCases.byId("02-").target());

    assertThat(e.source()).isEqualTo(Source.TEMPLATE);
    assertThat(e.fallbackReason()).isEqualTo("qwen3.5:4b's answer failed PgLens's checks twice");
    assertThat(e.violations()).anyMatch(v -> v.startsWith("sql:"));
    assertThat(e.summary()).startsWith("The planner estimates that an index on orders");
  }

  @Test
  void aModelThatIsDownCostsOneAttemptNotOnePerIndex() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    Explainer down =
        new Explainer(
            new OpenAiCompatibleClient(
                LlmSettings.defaults().withBaseUrl("http://127.0.0.1:" + port + "/v1")),
            null);

    Explanation first = down.explain(EvalCases.byId("01-").target());
    Explainer.Trace second = down.trace(EvalCases.byId("02-").target());

    assertThat(first.source()).isEqualTo(Source.TEMPLATE);
    assertThat(first.fallbackReason()).contains("is it running?");
    assertThat(second.explanation().fallbackReason()).isEqualTo(first.fallbackReason());
    assertThat(second.attempts()).isEmpty();
  }

  @Test
  void aTruncatedAnswerOnlyAffectsItsOwnIndex() {
    stub.reply(200, StubLlmServer.chatBody("{\"summary\":\"cut", "length")).replyContent(GOOD);

    Explanation first = explainer.explain(EvalCases.byId("01-").target());
    Explanation second = explainer.explain(EvalCases.byId("02-").target());

    assertThat(first.source()).isEqualTo(Source.TEMPLATE);
    assertThat(first.fallbackReason()).contains("max_tokens");
    assertThat(second.source()).isEqualTo(Source.LLM);
  }

  @Test
  void templateOnlyNeverCallsTheNetwork() {
    Explanation e = Explainer.templateOnly().explain(EvalCases.byId("08-").target());

    assertThat(e.source()).isEqualTo(Source.TEMPLATE);
    assertThat(e.fallbackReason()).isNull();
    assertThat(stub.requests()).isEmpty();
  }

  @Test
  void thePromptCarriesTheFactsAndCardsButNeverTheDdlOrCaveats() throws Exception {
    stub.replyContent(GOOD);
    explainer.explain(EvalCases.byId("02-").target());

    String prompt = JSON.readTree(stub.requests().get(0)).at("/messages/1/content").asText();
    assertThat(prompt)
        .contains("\"costDrop\" : \"98.7%\"")
        .contains("Selective sequential scan (R1)")
        .contains("Planner estimates are not runtimes")
        .doesNotContain("CREATE INDEX")
        .doesNotContain("Estimated index size") // footprint caveat
        .doesNotContain("sampled values") // value-range caveat
        .doesNotContain("Read-dominant"); // write load
  }

  private static String json(String summary, String why, String change) {
    try {
      return JSON.writeValueAsString(
          Map.of("summary", summary, "whyItIsSlow", why, "whatTheIndexChanges", change));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
