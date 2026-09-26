package com.pglens.server.explain;

import com.pglens.explain.llm.LlmSettings;
import com.pglens.explain.llm.OpenAiCompatibleClient;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The server's LLM endpoint (ADR-0043), built only when {@code pglens.explain.enabled=true}: the
 * explanation job is off by default, so a server without a model never tries to reach one.
 */
@Configuration
@ConditionalOnProperty(name = "pglens.explain.enabled", havingValue = "true")
class LlmConfig {

  @Bean
  LlmSettings llmSettings(
      @Value("${pglens.llm.base-url:" + LlmSettings.DEFAULT_BASE_URL + "}") String baseUrl,
      @Value("${pglens.llm.model:" + LlmSettings.DEFAULT_MODEL + "}") String model,
      @Value("${pglens.llm.embedding-model:" + LlmSettings.DEFAULT_EMBEDDING_MODEL + "}")
          String embeddingModel,
      @Value("${pglens.llm.api-key:}") String apiKey,
      @Value("${pglens.llm.timeout-seconds:120}") long timeoutSeconds,
      @Value("${pglens.llm.reasoning-effort:" + LlmSettings.DEFAULT_REASONING_EFFORT + "}")
          String reasoningEffort,
      @Value("${pglens.llm.allow-remote:false}") boolean allowRemote) {
    return new LlmSettings(
        URI.create(baseUrl),
        model,
        embeddingModel,
        apiKey.isBlank() ? null : apiKey,
        Duration.ofSeconds(timeoutSeconds),
        reasoningEffort,
        LlmSettings.DEFAULT_MAX_TOKENS,
        allowRemote);
  }

  @Bean
  OpenAiCompatibleClient llmClient(LlmSettings settings) {
    return new OpenAiCompatibleClient(settings);
  }
}
