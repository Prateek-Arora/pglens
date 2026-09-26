package com.pglens.explain.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pglens.explain.llm.LlmException.Kind;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A client for the two OpenAI API calls PgLens needs — {@code POST /chat/completions} and {@code
 * POST /embeddings} — over plain {@code java.net.http} (no SDK, no framework: ADR-0043). Works with
 * any runtime that speaks the OpenAI API.
 *
 * <p>Structured output: the first request asks for {@code response_format: json_schema}. On HTTP
 * 400 the client downgrades once to {@code json_object} (llama.cpp and some hosted APIs reject or
 * mishandle schemas), and if that is also rejected, drops {@code reasoning_effort} too (APIs that
 * reject the field for non-reasoning models). The downgrade sticks for the client's life. The
 * guard, not the runtime, is what enforces the answer's shape.
 */
public final class OpenAiCompatibleClient {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  /** One chat request: system + user message and the JSON schema the answer must follow. */
  public record ChatRequest(String system, String user, String schemaName, JsonNode schema) {}

  /** The answer's text content plus what the runtime reported about it. */
  public record ChatResponse(
      String content,
      String model,
      Integer promptTokens,
      Integer completionTokens,
      Duration elapsed,
      String responseFormat) {}

  private enum Format {
    JSON_SCHEMA,
    JSON_OBJECT,
    JSON_OBJECT_NO_REASONING
  }

  private final LlmSettings settings;
  private final HttpClient http;
  private final EndpointPolicy policy;
  private volatile Format format = Format.JSON_SCHEMA;

  public OpenAiCompatibleClient(LlmSettings settings) {
    this(settings, new EndpointPolicy());
  }

  public OpenAiCompatibleClient(LlmSettings settings, EndpointPolicy policy) {
    this.settings = settings;
    this.policy = policy;
    this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  }

  public LlmSettings settings() {
    return settings;
  }

  /** Checks the endpoint against the privacy policy without calling it. */
  public EndpointPolicy.Decision endpointDecision() {
    return policy.check(settings.baseUrl(), settings.allowRemote());
  }

  public ChatResponse chat(ChatRequest request) throws LlmException {
    requireAllowed();
    long start = System.nanoTime();
    while (true) {
      Format tried = format;
      HttpResponse<String> response = post("/chat/completions", chatBody(request, tried));
      if (response.statusCode() == 400 && tried != Format.JSON_OBJECT_NO_REASONING) {
        format = Format.values()[tried.ordinal() + 1];
        if (format == Format.JSON_OBJECT_NO_REASONING && blank(settings.reasoningEffort())) {
          throw httpError(response); // nothing left to drop
        }
        continue;
      }
      if (response.statusCode() / 100 != 2) {
        throw httpError(response);
      }
      return parseChat(response.body(), Duration.ofNanos(System.nanoTime() - start), tried);
    }
  }

  /** Embeds each input (callers add the model's task prefixes, e.g. nomic's). */
  public List<float[]> embed(List<String> inputs) throws LlmException {
    requireAllowed();
    ObjectNode body = JSON.createObjectNode();
    body.put("model", settings.embeddingModel());
    ArrayNode in = body.putArray("input");
    inputs.forEach(in::add);
    HttpResponse<String> response = post("/embeddings", body);
    if (response.statusCode() / 100 != 2) {
      throw httpError(response);
    }
    try {
      JsonNode data = JSON.readTree(response.body()).path("data");
      if (!data.isArray() || data.size() != inputs.size()) {
        throw new LlmException(
            Kind.UNPARSEABLE, "embeddings: expected " + inputs.size() + " vectors");
      }
      float[][] out = new float[inputs.size()][];
      for (JsonNode item : data) {
        int index = item.path("index").asInt(-1);
        JsonNode vector = item.path("embedding");
        if (index < 0 || index >= out.length || !vector.isArray()) {
          throw new LlmException(Kind.UNPARSEABLE, "embeddings: malformed item");
        }
        out[index] = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
          out[index][i] = (float) vector.get(i).asDouble();
        }
      }
      return List.of(out);
    } catch (IOException e) {
      throw new LlmException(Kind.UNPARSEABLE, "embeddings: response is not JSON", e);
    }
  }

  // --- internals --------------------------------------------------------------------------------

  private void requireAllowed() throws LlmException {
    EndpointPolicy.Decision d = endpointDecision();
    if (!d.allowed()) {
      throw new LlmException(Kind.REFUSED_REMOTE, d.reason());
    }
  }

  private ObjectNode chatBody(ChatRequest request, Format f) {
    ObjectNode body = JSON.createObjectNode();
    body.put("model", settings.model());
    body.put("temperature", 0);
    body.put("max_tokens", settings.maxTokens());
    if (!blank(settings.reasoningEffort()) && f != Format.JSON_OBJECT_NO_REASONING) {
      body.put("reasoning_effort", settings.reasoningEffort());
    }
    ArrayNode messages = body.putArray("messages");
    messages.addObject().put("role", "system").put("content", request.system());
    messages.addObject().put("role", "user").put("content", request.user());
    ObjectNode format = body.putObject("response_format");
    if (f == Format.JSON_SCHEMA) {
      format.put("type", "json_schema");
      ObjectNode schema = format.putObject("json_schema");
      schema.put("name", request.schemaName());
      schema.put("strict", true);
      schema.set("schema", request.schema());
    } else {
      format.put("type", "json_object");
    }
    return body;
  }

  private HttpResponse<String> post(String path, JsonNode body) throws LlmException {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(settings.baseUrl() + path))
            .timeout(settings.timeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    if (!blank(settings.apiKey())) {
      b.header("Authorization", "Bearer " + settings.apiKey());
    }
    try {
      return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    } catch (HttpConnectTimeoutException | ConnectException e) {
      throw new LlmException(
          Kind.UNREACHABLE, "no LLM answering at " + settings.baseUrl() + " (is it running?)", e);
    } catch (HttpTimeoutException e) {
      throw new LlmException(
          Kind.TIMEOUT,
          "no answer from " + settings.model() + " within " + settings.timeout().toSeconds() + " s",
          e);
    } catch (IOException e) {
      throw new LlmException(
          Kind.UNREACHABLE, "calling " + settings.baseUrl() + " failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmException(Kind.TIMEOUT, "interrupted while waiting for the LLM", e);
    }
  }

  private ChatResponse parseChat(String raw, Duration elapsed, Format f) throws LlmException {
    JsonNode root;
    try {
      root = JSON.readTree(raw);
    } catch (IOException e) {
      throw new LlmException(Kind.UNPARSEABLE, "chat: response is not JSON", e);
    }
    JsonNode choice = root.path("choices").path(0);
    if (choice.isMissingNode()) {
      throw new LlmException(Kind.UNPARSEABLE, "chat: response has no choices");
    }
    JsonNode message = choice.path("message");
    String content = message.path("content").asText("");
    if ("length".equals(choice.path("finish_reason").asText())) {
      throw new LlmException(
          Kind.TRUNCATED,
          "the answer hit max_tokens (" + settings.maxTokens() + ") and was cut off");
    }
    if (content.isBlank()) {
      boolean thought =
          !message.path("reasoning").asText("").isBlank()
              || !message.path("reasoning_content").asText("").isBlank();
      throw new LlmException(
          Kind.EMPTY,
          thought
              ? "the model only produced reasoning — turn thinking off (reasoning effort \"none\")"
              : "the model returned an empty answer");
    }
    JsonNode usage = root.path("usage");
    return new ChatResponse(
        content,
        root.path("model").asText(settings.model()),
        usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null,
        usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null,
        elapsed,
        f == Format.JSON_SCHEMA ? "json_schema" : "json_object");
  }

  private static LlmException httpError(HttpResponse<String> response) {
    String body = response.body() == null ? "" : response.body().strip();
    List<String> parts = new ArrayList<>();
    parts.add("HTTP " + response.statusCode());
    if (!body.isEmpty()) {
      parts.add(body.length() > 200 ? body.substring(0, 200) + "…" : body);
    }
    return new LlmException(Kind.HTTP_ERROR, String.join(": ", parts));
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
