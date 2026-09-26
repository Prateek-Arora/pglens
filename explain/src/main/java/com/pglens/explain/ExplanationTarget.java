package com.pglens.explain;

import com.pglens.engine.model.QueryReport;
import com.pglens.engine.model.RankedRecommendation;
import com.pglens.engine.model.Recommendation;
import com.pglens.engine.model.ScanReport;
import com.pglens.engine.model.ValidationResult.Status;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One index to explain, with the query it was ranked for. {@code alsoValidatedForOtherQueries}
 * counts the other queries whose own recommendation this index was HypoPG-validated to serve (the
 * report's covered recs). Pure.
 */
public record ExplanationTarget(
    QueryReport query, Recommendation recommendation, int alsoValidatedForOtherQueries) {

  /**
   * What {@code --plain} explains: the first {@code limit} distinct indexes worth creating (the
   * report's actionable ranked recs, in rank order), then — within the same limit — indexes PgLens
   * surfaced but couldn't planner-check (GIN/GiST), which are the hardest to judge without an
   * explanation. Covered recs are never targets: they aren't distinct indexes.
   */
  public static List<ExplanationTarget> select(ScanReport report, int limit) {
    Map<Long, QueryReport> byId =
        report.queries().stream()
            .collect(Collectors.toMap(QueryReport::queryId, Function.identity(), (a, b) -> a));
    List<ExplanationTarget> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (RankedRecommendation r : report.topRecommendations()) {
      QueryReport q = byId.get(r.queryId());
      if (out.size() >= limit) {
        break;
      }
      if (!r.actionable() || q == null || !seen.add(r.candidate().ddl())) {
        continue;
      }
      out.add(new ExplanationTarget(q, r.recommendation(), covered(report, r.candidate().ddl())));
    }
    for (QueryReport q : report.queries()) {
      for (Recommendation rec : q.recommendations()) {
        if (out.size() >= limit) {
          return out;
        }
        if (rec.validation().status() == Status.NOT_PLANNER_VALIDATED
            && !rec.candidate().plannerValidatable()
            && seen.add(rec.candidate().ddl())) {
          out.add(new ExplanationTarget(q, rec, 0));
        }
      }
    }
    return out;
  }

  /**
   * What {@code pglens explain <queryid> --plain} explains: this query's planner-validated indexes,
   * then the ones PgLens couldn't planner-check, up to {@code limit}.
   */
  public static List<ExplanationTarget> forQuery(QueryReport query, int limit) {
    List<ExplanationTarget> out = new ArrayList<>();
    for (boolean validated : new boolean[] {true, false}) {
      for (Recommendation rec : query.recommendations()) {
        boolean ok =
            validated
                ? rec.isRecommended()
                : rec.validation().status() == Status.NOT_PLANNER_VALIDATED
                    && !rec.candidate().plannerValidatable();
        if (ok && out.size() < limit) {
          out.add(new ExplanationTarget(query, rec, 0));
        }
      }
    }
    return out;
  }

  private static int covered(ScanReport report, String ddl) {
    return (int)
        report.topRecommendations().stream()
            .filter(o -> o.coveredBySubsumer() && ddl.equals(o.subsumedByDdl()))
            .count();
  }
}
