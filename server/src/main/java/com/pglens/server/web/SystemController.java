package com.pglens.server.web;

import com.pglens.explain.llm.EndpointPolicy;
import com.pglens.explain.llm.LlmSettings;
import com.pglens.server.explain.ExplanationService;
import com.pglens.server.explain.ExplanationService.PassStatus;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this server is running (ADR-0052): its version, and how plain-language explanations are
 * written — PgLens's own template, or a model (which, where, and how its last pass went). The API
 * key is never part of it.
 */
@RestController
class SystemController {

  /**
   * {@code mode}: {@code TEMPLATE} (no model configured — the default) or {@code LLM}. {@code
   * endpointHost} is the model's host only; {@code remote} is true for a host outside this machine
   * and private network. The counts are the cached explanations by who wrote them.
   */
  record ExplanationSetup(
      String mode,
      @Nullable String model,
      @Nullable String endpointHost,
      boolean remote,
      @Nullable Instant lastPassAt,
      @Nullable Integer lastPassWritten,
      @Nullable Integer lastPassTemplateFallbacks,
      @Nullable String lastFallbackReason,
      @Nullable String docsProblem,
      long writtenByModel,
      long writtenByTemplate) {}

  record SystemStatus(@Nullable String version, ExplanationSetup explanations) {}

  private final ObjectProvider<ExplanationService> explanations;
  private final ObjectProvider<BuildProperties> build;
  private final JdbcTemplate jdbc;

  SystemController(
      ObjectProvider<ExplanationService> explanations,
      ObjectProvider<BuildProperties> build,
      JdbcTemplate jdbc) {
    this.explanations = explanations;
    this.build = build;
    this.jdbc = jdbc;
  }

  @GetMapping("/api/v1/system")
  SystemStatus system() {
    BuildProperties info = build.getIfAvailable();
    return new SystemStatus(info == null ? null : info.getVersion(), explanations());
  }

  private ExplanationSetup explanations() {
    Map<String, Long> bySource = new HashMap<>();
    jdbc.query(
        "SELECT source, count(*) FROM explanations GROUP BY source",
        rs -> {
          bySource.put(rs.getString(1), rs.getLong(2));
        });
    long byModel = bySource.getOrDefault("LLM", 0L);
    long byTemplate = bySource.getOrDefault("TEMPLATE", 0L);
    ExplanationService service = explanations.getIfAvailable();
    if (service == null) {
      return new ExplanationSetup(
          "TEMPLATE", null, null, false, null, null, null, null, null, byModel, byTemplate);
    }
    LlmSettings settings = service.settings();
    EndpointPolicy.Decision endpoint =
        new EndpointPolicy().check(settings.baseUrl(), settings.allowRemote());
    PassStatus last = service.lastPass();
    return new ExplanationSetup(
        "LLM",
        settings.model(),
        endpoint.host(),
        endpoint.remote(),
        last == null ? null : last.at(),
        last == null ? null : last.written(),
        last == null ? null : last.templateFallbacks(),
        last == null ? null : last.lastFallbackReason(),
        last == null ? null : last.docsProblem(),
        byModel,
        byTemplate);
  }
}
