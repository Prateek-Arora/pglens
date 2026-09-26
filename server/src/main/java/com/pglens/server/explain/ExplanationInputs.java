package com.pglens.server.explain;

import com.pglens.engine.candidate.IndexCandidateGenerator;
import com.pglens.engine.detect.AntiPatternDetector;
import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.Finding;
import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.QueryReport;
import com.pglens.engine.model.Recommendation;
import com.pglens.engine.model.ValidationResult;
import com.pglens.engine.parse.PlanParser;
import com.pglens.explain.ExplanationTarget;
import com.pglens.server.advice.AdviceService;
import com.pglens.server.advice.IndexAdvice;
import com.pglens.server.persistence.CatalogRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Rebuilds, from what the server already stores, the same {@link ExplanationTarget} the CLI builds
 * from a scan report: the index's top query (text, measured totals), its findings (the pure
 * detector re-run on the persisted plan — findings aren't stored), the candidate with the rules it
 * came from, and the verdict the agent reported. Same facts in, same explanation out.
 */
@Component
public class ExplanationInputs {

  private final JdbcTemplate jdbc;
  private final AdviceService advice;
  private final CatalogRepository catalogs;
  private final PlanParser planParser = new PlanParser();
  private final AntiPatternDetector detector = new AntiPatternDetector();
  private final IndexCandidateGenerator generator = new IndexCandidateGenerator();

  public ExplanationInputs(JdbcTemplate jdbc, AdviceService advice, CatalogRepository catalogs) {
    this.jdbc = jdbc;
    this.advice = advice;
    this.catalogs = catalogs;
  }

  /** The top {@code limit} actionable indexes of this db, each with its highest-scoring query. */
  public List<ExplanationTarget> targets(long dbId, int limit) {
    CatalogSnapshot catalog = catalogs.load(dbId);
    List<ExplanationTarget> out = new ArrayList<>();
    for (IndexAdvice a : advice.advice(dbId)) {
      if (out.size() >= limit) {
        break;
      }
      if (!a.actionable() || a.queries().isEmpty()) {
        continue;
      }
      long queryId = a.queries().get(0).queryId(); // sorted by estimated saving, highest first
      target(dbId, queryId, a.ddl(), a.queries().size() - 1, catalog).ifPresent(out::add);
    }
    return out;
  }

  Optional<ExplanationTarget> target(
      long dbId, long queryId, String ddl, int otherQueries, CatalogSnapshot catalog) {
    List<QueryRow> rows =
        jdbc.query(
            "SELECT t.normalized_text, t.plan_json, t.truncated, c.calls, c.total_exec_time_ms "
                + "FROM query_texts t JOIN query_cumulative c USING (db_id, queryid) "
                + "WHERE t.db_id = ? AND t.queryid = ? AND t.plan_captured",
            (rs, n) ->
                new QueryRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getBoolean(3),
                    rs.getLong(4),
                    rs.getDouble(5)),
            dbId,
            queryId);
    List<ValidationResult> verdicts =
        jdbc.query(
            "SELECT status, before_cost, after_cost, relative_drop, used, reason "
                + "FROM recommendations WHERE db_id = ? AND queryid = ? AND ddl = ?",
            (rs, n) ->
                new ValidationResult(
                    ValidationResult.Status.valueOf(rs.getString(1)),
                    (Double) rs.getObject(2),
                    (Double) rs.getObject(3),
                    (Double) rs.getObject(4),
                    Boolean.TRUE.equals(rs.getObject(5)),
                    rs.getString(6)),
            dbId,
            queryId,
            ddl);
    if (rows.isEmpty() || verdicts.isEmpty()) {
      return Optional.empty();
    }
    QueryRow q = rows.get(0);
    List<Finding> findings;
    try {
      findings = detector.detect(planParser.parse(q.planJson()), catalog);
    } catch (RuntimeException malformedPlan) {
      findings = List.of();
    }
    IndexCandidate candidate =
        generator.generate(findings).stream()
            .filter(c -> c.ddl().equals(ddl))
            .findFirst()
            // The catalog moved on since validation: explain the index without rule-matched
            // findings rather than guess which rule produced it.
            .orElseGet(() -> IndexCandidate.parseDdl(ddl).orElse(null));
    if (candidate == null) {
      return Optional.empty();
    }
    double mean = q.calls() == 0 ? 0 : q.totalMs() / q.calls();
    QueryReport report =
        new QueryReport(
            queryId,
            q.text(),
            q.truncated(),
            q.calls(),
            q.totalMs(),
            mean,
            true,
            null,
            findings,
            List.of());
    return Optional.of(
        new ExplanationTarget(
            report, new Recommendation(candidate, verdicts.get(0)), Math.max(0, otherQueries)));
  }

  private record QueryRow(
      String text, String planJson, boolean truncated, long calls, double totalMs) {}
}
