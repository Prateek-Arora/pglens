package com.pglens.server.explain;

import com.pglens.explain.Explainer;
import com.pglens.explain.Explanation;
import com.pglens.explain.ExplanationFacts;
import com.pglens.explain.ExplanationTarget;
import com.pglens.explain.FactsBuilder;
import com.pglens.explain.FactsHash;
import com.pglens.explain.PromptBuilder;
import com.pglens.explain.ReferenceSource;
import com.pglens.explain.llm.LlmException;
import com.pglens.explain.llm.OpenAiCompatibleClient;
import com.pglens.server.persistence.MonitoredDb;
import com.pglens.server.persistence.MonitoredDbRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes plain-language explanations for each monitored db's top indexes into the {@code
 * explanations} cache, for the Phase 4 dashboard to read (ADR-0043). Off by default ({@code
 * pglens.explain.enabled}); runs on its own schedule, apart from the analysis job, so a slow model
 * never delays analysis. Each pass explains at most {@code max-per-pass} indexes whose exact facts
 * + model + prompt aren't cached yet.
 *
 * <p>Replicas: not locked. Two replicas would at worst explain the same index twice (the insert is
 * an idempotent upsert); a lock held across minutes of LLM calls would cost more than that.
 */
@Service
@ConditionalOnProperty(name = "pglens.explain.enabled", havingValue = "true")
public class ExplanationService {

  private static final Logger log = LoggerFactory.getLogger(ExplanationService.class);

  private final MonitoredDbRepository monitoredDbs;
  private final ExplanationInputs inputs;
  private final ExplanationRepository cache;
  private final OpenAiCompatibleClient client;
  private final KnowledgeStore knowledge;
  private final int topN;
  private final int maxPerPass;
  private final long retryAfterMs;
  private final boolean docsInPrompt;

  public ExplanationService(
      MonitoredDbRepository monitoredDbs,
      ExplanationInputs inputs,
      ExplanationRepository cache,
      OpenAiCompatibleClient client,
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      @Value("${pglens.explain.top-n:5}") int topN,
      @Value("${pglens.explain.max-per-pass:5}") int maxPerPass,
      @Value("${pglens.explain.retry-after-ms:3600000}") long retryAfterMs,
      @Value("${pglens.explain.docs-in-prompt:false}") boolean docsInPrompt) {
    this.monitoredDbs = monitoredDbs;
    this.inputs = inputs;
    this.cache = cache;
    this.client = client;
    this.knowledge = new KnowledgeStore(jdbc, tx, client);
    this.topN = topN;
    this.maxPerPass = maxPerPass;
    this.retryAfterMs = retryAfterMs;
    this.docsInPrompt = docsInPrompt;
  }

  @Scheduled(
      fixedDelayString = "${pglens.explain.interval-ms:300000}",
      initialDelayString = "${pglens.explain.initial-delay-ms:60000}")
  public void runScheduled() {
    try {
      run();
    } catch (RuntimeException e) {
      log.warn("explanation pass failed: {}", e.toString());
    }
  }

  /** One pass over every db; returns how many explanations were written. */
  public int run() {
    ReferenceSource refs = ReferenceSource.NONE;
    try {
      int embedded = knowledge.ensureLoaded();
      if (embedded > 0) {
        log.info("embedded {} PostgreSQL-docs passages into pgvector", embedded);
      }
      refs = new PgvectorReferenceSource(knowledge);
    } catch (LlmException | RuntimeException e) {
      log.info("docs retrieval unavailable this pass: {}", e.getMessage());
    }
    // A fresh explainer per pass: its "model is down" shortcut must not outlive the pass.
    Explainer explainer = new Explainer(client, refs, docsInPrompt);
    String promptVersion =
        PromptBuilder.VERSION + (docsInPrompt ? "+" + KnowledgeStore.CORPUS_VERSION : "");
    Instant now = Instant.now();
    int written = 0;
    for (MonitoredDb db : monitoredDbs.findAll()) {
      for (ExplanationTarget target : inputs.targets(db.id(), topN)) {
        if (written >= maxPerPass) {
          return written;
        }
        ExplanationFacts facts = FactsBuilder.build(target);
        ExplanationRepository.Key key =
            new ExplanationRepository.Key(
                db.id(),
                target.query().queryId(),
                target.recommendation().candidate().ddl(),
                FactsHash.of(facts),
                client.settings().model(),
                promptVersion);
        if (cache.isFresh(key, now)) {
          continue;
        }
        Explainer.Trace trace = explainer.trace(target);
        Explanation e = trace.explanation();
        // An outage is transient — retry later; a rejected answer repeats at temperature 0.
        Instant retryAfter = trace.failure() == null ? null : now.plusMillis(retryAfterMs);
        cache.put(key, e, retryAfter);
        written++;
        log.info(
            "explained {} for db '{}' ({}{})",
            key.ddl(),
            db.name(),
            e.source(),
            e.fallbackReason() == null ? "" : ": " + e.fallbackReason());
      }
    }
    return written;
  }
}
