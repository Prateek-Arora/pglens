package com.pglens.server.explain;

import com.pglens.explain.Explanation;
import com.pglens.explain.ExplanationFacts;
import com.pglens.explain.ExplanationTarget;
import com.pglens.explain.FactsBuilder;
import com.pglens.explain.FactsHash;
import com.pglens.explain.TemplateExplainer;
import com.pglens.server.advice.AdviceService;
import com.pglens.server.advice.IndexAdvice;
import com.pglens.server.persistence.CatalogRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The explanations the read API serves (ADR-0044). Works with no LLM at all: the template is
 * computed on every read from the stored facts. When the explanation job has cached an LLM answer
 * for exactly the same facts (same hash), that answer is shown instead, labeled with its model and
 * prompt version; changed facts never reuse an old answer.
 */
@Component
public class ExplanationReader {

  /**
   * One index's explanation for one query.
   *
   * @param source {@code TEMPLATE} (computed now) or {@code LLM} (cached, checked by the guard)
   * @param model the model that wrote an LLM explanation; {@code null} for the template
   * @param generatedAt when an LLM explanation was written; {@code null} for the template
   */
  public record ExplanationView(
      String ddl,
      String source,
      String summary,
      String whyItIsSlow,
      String whatTheIndexChanges,
      List<String> docs,
      String model,
      String promptVersion,
      Instant generatedAt) {}

  private final JdbcTemplate jdbc;
  private final ExplanationInputs inputs;
  private final ExplanationRepository cache;
  private final AdviceService advice;
  private final CatalogRepository catalogs;

  public ExplanationReader(
      JdbcTemplate jdbc,
      ExplanationInputs inputs,
      ExplanationRepository cache,
      AdviceService advice,
      CatalogRepository catalogs) {
    this.jdbc = jdbc;
    this.inputs = inputs;
    this.cache = cache;
    this.advice = advice;
    this.catalogs = catalogs;
  }

  /**
   * An explanation per index recommended for this query: planner-validated ones first (highest
   * estimated saving first), then the ones PgLens surfaced but couldn't planner-check (GIN/GiST).
   */
  public List<ExplanationView> forQuery(long dbId, long queryId) {
    List<String> ddls =
        jdbc.queryForList(
            "SELECT ddl FROM recommendations WHERE db_id = ? AND queryid = ? "
                + "AND status IN ('PLANNER_VALIDATED', 'NOT_PLANNER_VALIDATED') "
                + "ORDER BY status = 'PLANNER_VALIDATED' DESC, estimated_ms_saved DESC NULLS LAST, "
                + "ddl",
            String.class,
            dbId,
            queryId);
    if (ddls.isEmpty()) {
      return List.of();
    }
    // The same "also validated for N other queries" count the explanation job uses, so the facts —
    // and their hash — match what it cached.
    Map<String, Integer> otherQueries =
        advice.advice(dbId).stream()
            .collect(Collectors.toMap(IndexAdvice::ddl, a -> a.queries().size() - 1, (a, b) -> a));
    var catalog = catalogs.load(dbId);
    List<ExplanationView> out = new ArrayList<>();
    for (String ddl : ddls) {
      Optional<ExplanationTarget> target =
          inputs.target(dbId, queryId, ddl, otherQueries.getOrDefault(ddl, 0), catalog);
      target.ifPresent(t -> out.add(explain(dbId, queryId, ddl, t)));
    }
    return out;
  }

  private ExplanationView explain(long dbId, long queryId, String ddl, ExplanationTarget target) {
    ExplanationFacts facts = FactsBuilder.build(target);
    Optional<ExplanationRepository.CachedLlm> llm =
        cache.cachedLlm(dbId, queryId, ddl, FactsHash.of(facts));
    if (llm.isPresent()) {
      Explanation e = llm.get().explanation();
      return view(e, llm.get().promptVersion(), llm.get().createdAt());
    }
    return view(TemplateExplainer.explain(facts, ddl), null, null);
  }

  private static ExplanationView view(Explanation e, String promptVersion, Instant generatedAt) {
    return new ExplanationView(
        e.ddl(),
        e.source().name(),
        e.summary(),
        e.whyItIsSlow(),
        e.whatTheIndexChanges(),
        e.docs(),
        e.model(),
        promptVersion,
        generatedAt);
  }
}
