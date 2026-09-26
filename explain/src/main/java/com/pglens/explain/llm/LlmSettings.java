package com.pglens.explain.llm;

import java.net.URI;
import java.time.Duration;

/**
 * How to reach an OpenAI-compatible chat/embeddings endpoint (ADR-0043). The defaults match a local
 * Ollama with the model the Phase 3 spike chose; any runtime that speaks {@code
 * /v1/chat/completions} works (Docker Model Runner, llama.cpp, LM Studio, vLLM, hosted APIs).
 *
 * @param baseUrl the API root, ending in {@code /v1}
 * @param apiKey sent as a bearer token when set (hosted APIs); never logged
 * @param reasoningEffort sent as {@code reasoning_effort}; {@code "none"} turns a thinking model's
 *     thinking off (with it on, the spike's call took 211 s and returned nothing). Blank = omit,
 *     for runtimes that reject the field.
 * @param allowRemote permit an endpoint that isn't on this machine or a private network — query
 *     text then leaves your infrastructure (see {@link EndpointPolicy})
 */
public record LlmSettings(
    URI baseUrl,
    String model,
    String embeddingModel,
    String apiKey,
    Duration timeout,
    String reasoningEffort,
    int maxTokens,
    boolean allowRemote) {

  public static final String DEFAULT_BASE_URL = "http://localhost:11434/v1";
  public static final String DEFAULT_MODEL = "qwen3.5:4b";
  public static final String DEFAULT_EMBEDDING_MODEL = "nomic-embed-text";
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
  public static final String DEFAULT_REASONING_EFFORT = "none";
  public static final int DEFAULT_MAX_TOKENS = 600;

  public LlmSettings {
    if (baseUrl == null || baseUrl.getHost() == null) {
      throw new IllegalArgumentException("LLM base URL needs a host, e.g. " + DEFAULT_BASE_URL);
    }
    String path = baseUrl.getPath() == null ? "" : baseUrl.getPath();
    if (path.endsWith("/")) {
      baseUrl = URI.create(baseUrl.toString().replaceAll("/+$", ""));
    }
  }

  public static LlmSettings defaults() {
    return new LlmSettings(
        URI.create(DEFAULT_BASE_URL),
        DEFAULT_MODEL,
        DEFAULT_EMBEDDING_MODEL,
        null,
        DEFAULT_TIMEOUT,
        DEFAULT_REASONING_EFFORT,
        DEFAULT_MAX_TOKENS,
        false);
  }

  public LlmSettings withBaseUrl(String url) {
    return new LlmSettings(
        URI.create(url),
        model,
        embeddingModel,
        apiKey,
        timeout,
        reasoningEffort,
        maxTokens,
        allowRemote);
  }

  public LlmSettings withModel(String name) {
    return new LlmSettings(
        baseUrl, name, embeddingModel, apiKey, timeout, reasoningEffort, maxTokens, allowRemote);
  }

  public LlmSettings withApiKey(String key) {
    return new LlmSettings(
        baseUrl, model, embeddingModel, key, timeout, reasoningEffort, maxTokens, allowRemote);
  }

  public LlmSettings withTimeout(Duration t) {
    return new LlmSettings(
        baseUrl, model, embeddingModel, apiKey, t, reasoningEffort, maxTokens, allowRemote);
  }

  public LlmSettings withAllowRemote(boolean allow) {
    return new LlmSettings(
        baseUrl, model, embeddingModel, apiKey, timeout, reasoningEffort, maxTokens, allow);
  }

  public LlmSettings withReasoningEffort(String effort) {
    return new LlmSettings(
        baseUrl, model, embeddingModel, apiKey, timeout, effort, maxTokens, allowRemote);
  }

  /** Never prints the API key. */
  @Override
  public String toString() {
    return "LlmSettings[baseUrl="
        + baseUrl
        + ", model="
        + model
        + ", apiKey="
        + (apiKey == null || apiKey.isBlank() ? "none" : "set")
        + "]";
  }
}
