package com.pglens.cli;

import com.pglens.explain.llm.LlmSettings;
import java.time.Duration;
import java.util.Locale;
import picocli.CommandLine.Option;

/**
 * The {@code --plain} options shared by {@code scan} and {@code explain} (Phase 3, ADR-0043). Each
 * LLM setting falls back to an environment variable, then to the default: a local Ollama running
 * {@code qwen3.5:4b}. The API key is read from {@code PGLENS_LLM_API_KEY} only, so it never lands
 * in shell history.
 */
class LlmOptions {

  @Option(
      names = "--plain",
      arity = "0..1",
      fallbackValue = "template",
      paramLabel = "MODE",
      description =
          "Add plain-language explanations of the top indexes: template (default; PgLens's own"
              + " wording with every number) or llm (a local model rewrites it; falls back to the"
              + " template if the model is unavailable or fails PgLens's checks).")
  String plain;

  @Option(
      names = "--plain-top",
      paramLabel = "N",
      description = "How many indexes to explain (default: ${DEFAULT-VALUE}).")
  int plainTop = 3;

  @Option(
      names = "--llm-url",
      paramLabel = "URL",
      description =
          "OpenAI-compatible API root (env PGLENS_LLM_URL; default "
              + LlmSettings.DEFAULT_BASE_URL
              + ").")
  String llmUrl;

  @Option(
      names = "--llm-model",
      paramLabel = "NAME",
      description = "Model name (env PGLENS_LLM_MODEL; default " + LlmSettings.DEFAULT_MODEL + ").")
  String llmModel;

  @Option(
      names = "--llm-timeout",
      paramLabel = "SECONDS",
      description = "Per-answer timeout (default: 120).")
  Integer llmTimeoutSeconds;

  @Option(
      names = "--allow-remote-llm",
      description =
          "Allow an LLM endpoint outside this machine/private network. Query text and table names"
              + " are then sent to it.")
  boolean allowRemote;

  boolean enabled() {
    return plain != null;
  }

  /** True for {@code --plain=template}; throws on an unknown mode. */
  boolean templateOnly() {
    return switch (plain.toLowerCase(Locale.ROOT)) {
      case "llm" -> false;
      case "template" -> true;
      default ->
          throw new IllegalArgumentException(
              "--plain must be llm or template (got '" + plain + "')");
    };
  }

  LlmSettings settings() {
    LlmSettings s = LlmSettings.defaults();
    String url = firstNonBlank(llmUrl, System.getenv("PGLENS_LLM_URL"));
    if (url != null) {
      s = s.withBaseUrl(url);
    }
    String model = firstNonBlank(llmModel, System.getenv("PGLENS_LLM_MODEL"));
    if (model != null) {
      s = s.withModel(model);
    }
    String key = System.getenv("PGLENS_LLM_API_KEY");
    if (key != null && !key.isBlank()) {
      s = s.withApiKey(key);
    }
    if (llmTimeoutSeconds != null) {
      s = s.withTimeout(Duration.ofSeconds(llmTimeoutSeconds));
    }
    return s.withAllowRemote(allowRemote);
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    return b != null && !b.isBlank() ? b : null;
  }
}
