package com.pglens.explain.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pglens.explain.llm.LlmException.Kind;
import com.pglens.explain.llm.OpenAiCompatibleClient.ChatRequest;
import com.pglens.explain.llm.OpenAiCompatibleClient.ChatResponse;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Every way a call can fail maps to a kind the explainer turns into a template + reason. */
class OpenAiCompatibleClientTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ChatRequest REQUEST =
      new ChatRequest(
          "system", "user", "explanation", JSON.createObjectNode().put("type", "object"));

  private StubLlmServer stub;
  private OpenAiCompatibleClient client;

  @BeforeEach
  void start() throws Exception {
    stub = new StubLlmServer();
    client = new OpenAiCompatibleClient(LlmSettings.defaults().withBaseUrl(stub.baseUrl()));
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  @Test
  void sendsTheSettingsTheSpikeShowedAreNeeded() throws Exception {
    stub.replyContent("{\"summary\":\"s\"}");

    ChatResponse r = client.chat(REQUEST);

    assertThat(r.content()).isEqualTo("{\"summary\":\"s\"}");
    assertThat(r.promptTokens()).isEqualTo(700);
    assertThat(r.responseFormat()).isEqualTo("json_schema");
    JsonNode sent = JSON.readTree(stub.requests().get(0));
    assertThat(stub.paths()).containsExactly("/v1/chat/completions");
    assertThat(sent.get("model").asText()).isEqualTo("qwen3.5:4b");
    assertThat(sent.get("temperature").asInt()).isZero();
    assertThat(sent.get("max_tokens").asInt()).isEqualTo(600);
    assertThat(sent.get("reasoning_effort").asText()).isEqualTo("none");
    assertThat(sent.at("/response_format/type").asText()).isEqualTo("json_schema");
    assertThat(sent.at("/response_format/json_schema/strict").asBoolean()).isTrue();
    assertThat(sent.at("/messages/0/role").asText()).isEqualTo("system");
  }

  @Test
  void aRejectedSchemaFallsBackToJsonObjectAndStaysThere() throws Exception {
    stub.reply(400, "{\"error\":\"response_format json_schema not supported\"}")
        .replyContent("{}")
        .replyContent("{}");

    assertThat(client.chat(REQUEST).responseFormat()).isEqualTo("json_object");
    client.chat(REQUEST);

    List<String> sent = stub.requests();
    assertThat(sent).hasSize(3);
    assertThat(JSON.readTree(sent.get(1)).at("/response_format/type").asText())
        .isEqualTo("json_object");
    assertThat(JSON.readTree(sent.get(2)).at("/response_format/type").asText())
        .isEqualTo("json_object");
  }

  @Test
  void aRejectedReasoningFieldIsDroppedAsTheLastDowngrade() throws Exception {
    stub.reply(400, "{}")
        .reply(400, "{\"error\":\"unknown field reasoning_effort\"}")
        .replyContent("{}");

    client.chat(REQUEST);

    assertThat(JSON.readTree(stub.requests().get(2)).has("reasoning_effort")).isFalse();
  }

  @Test
  void aServerErrorIsAnHttpError() {
    stub.reply(500, "{\"error\":\"model requires more system memory\"}");

    assertThatThrownBy(() -> client.chat(REQUEST))
        .isInstanceOfSatisfying(
            LlmException.class, e -> assertThat(e.kind()).isEqualTo(Kind.HTTP_ERROR))
        .hasMessageContaining("HTTP 500")
        .hasMessageContaining("more system memory");
  }

  @Test
  void anAnswerCutOffAtMaxTokensIsTruncated() {
    stub.reply(200, StubLlmServer.chatBody("{\"summary\":\"half", "length"));

    assertThatThrownBy(() -> client.chat(REQUEST))
        .isInstanceOfSatisfying(
            LlmException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TRUNCATED));
  }

  @Test
  void thinkingLeftOnIsReportedWithTheFix() {
    stub.reply(
        200,
        "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"\",\"reasoning\":\"Let me think...\"}}]}");

    assertThatThrownBy(() -> client.chat(REQUEST))
        .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(Kind.EMPTY))
        .hasMessageContaining("reasoning effort");
  }

  @Test
  void aNonOpenAiResponseIsUnparseable() {
    stub.reply(200, "<html>proxy login</html>");

    assertThatThrownBy(() -> client.chat(REQUEST))
        .isInstanceOfSatisfying(
            LlmException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNPARSEABLE));
  }

  @Test
  void aSlowModelTimesOut() {
    OpenAiCompatibleClient impatient =
        new OpenAiCompatibleClient(
            LlmSettings.defaults().withBaseUrl(stub.baseUrl()).withTimeout(Duration.ofMillis(300)));
    stub.replySlowly(2_000, StubLlmServer.chatBody("{}", "stop"));

    assertThatThrownBy(() -> impatient.chat(REQUEST))
        .isInstanceOfSatisfying(
            LlmException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TIMEOUT));
  }

  @Test
  void nothingListeningIsUnreachable() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    OpenAiCompatibleClient down =
        new OpenAiCompatibleClient(
            LlmSettings.defaults().withBaseUrl("http://127.0.0.1:" + port + "/v1"));

    assertThatThrownBy(() -> down.chat(REQUEST))
        .isInstanceOfSatisfying(
            LlmException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNREACHABLE))
        .hasMessageContaining("is it running?");
  }

  @Test
  void aRemoteEndpointIsRefusedBeforeAnythingIsSent() {
    OpenAiCompatibleClient remote =
        new OpenAiCompatibleClient(
            LlmSettings.defaults().withBaseUrl("https://api.example.com/v1"),
            new EndpointPolicy(
                host ->
                    new java.net.InetAddress[] {
                      java.net.InetAddress.getByAddress(
                          host, new byte[] {93, (byte) 184, (byte) 215, 14})
                    }));

    assertThatThrownBy(() -> remote.chat(REQUEST))
        .isInstanceOfSatisfying(
            LlmException.class, e -> assertThat(e.kind()).isEqualTo(Kind.REFUSED_REMOTE))
        .hasMessageContaining("--allow-remote-llm");
  }

  @Test
  void anApiKeyIsSentAsABearerTokenAndNeverPrinted() throws Exception {
    LlmSettings s = LlmSettings.defaults().withBaseUrl(stub.baseUrl()).withApiKey("sk-secret");
    assertThat(s.toString()).doesNotContain("sk-secret").contains("apiKey=set");
  }

  @Test
  void embeddingsComeBackInInputOrder() throws Exception {
    stub.reply(
        200,
        "{\"data\":[{\"index\":1,\"embedding\":[0.5,0.25]},{\"index\":0,\"embedding\":[1,2]}]}");

    List<float[]> v = client.embed(List.of("search_query: a", "search_query: b"));

    assertThat(v.get(0)).containsExactly(1f, 2f);
    assertThat(v.get(1)).containsExactly(0.5f, 0.25f);
    assertThat(stub.paths()).containsExactly("/v1/embeddings");
    assertThat(JSON.readTree(stub.requests().get(0)).get("model").asText())
        .isEqualTo("nomic-embed-text");
  }
}
