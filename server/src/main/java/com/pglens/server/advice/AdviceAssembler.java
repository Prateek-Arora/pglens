package com.pglens.server.advice;

import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.TableWriteLoad;
import com.pglens.server.advice.IndexAdvice.QueryEvidence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Groups validated recommendation rows into per-index {@link IndexAdvice} (ADR-0038). Pure — no
 * Spring, no I/O — so the grouping and redundancy rules are unit-tested without a database.
 */
public final class AdviceAssembler {

  private AdviceAssembler() {}

  /** One PLANNER_VALIDATED recommendation row. */
  public record ValidatedRow(
      String ddl,
      String accessMethod,
      long queryId,
      Double estimatedMsSaved,
      String scoreBasis,
      double relativeDrop,
      String rangeLabel,
      String footprintLabel) {}

  /**
   * @param rows every validated (query, index) row for one db
   * @param writeLoadByTable the write-load assessment per lowercased table (may lack a table)
   * @return actionable advice by total estimated savings (descending), then redundant advice
   */
  public static List<IndexAdvice> assemble(
      List<ValidatedRow> rows, Map<String, TableWriteLoad> writeLoadByTable) {
    Map<String, List<ValidatedRow>> byDdl = new LinkedHashMap<>();
    rows.forEach(r -> byDdl.computeIfAbsent(r.ddl(), k -> new ArrayList<>()).add(r));

    List<IndexAdvice> out = new ArrayList<>();
    for (var e : byDdl.entrySet()) {
      Optional<IndexCandidate> candidate = IndexCandidate.parseDdl(e.getKey());
      String table = candidate.map(IndexCandidate::table).orElse(null);
      List<QueryEvidence> evidence =
          e.getValue().stream()
              .map(
                  r ->
                      new QueryEvidence(
                          r.queryId(),
                          r.estimatedMsSaved() == null ? 0.0 : r.estimatedMsSaved(),
                          r.scoreBasis(),
                          r.relativeDrop(),
                          r.rangeLabel()))
              .sorted(Comparator.comparingDouble(QueryEvidence::estimatedMsSaved).reversed())
              .toList();
      out.add(
          new IndexAdvice(
              e.getKey(),
              table,
              e.getValue().get(0).accessMethod(),
              evidence.stream().mapToDouble(QueryEvidence::estimatedMsSaved).sum(),
              evidence,
              e.getValue().stream()
                  .map(ValidatedRow::footprintLabel)
                  .filter(l -> l != null)
                  .findFirst()
                  .orElse(null),
              redundantWith(e.getKey(), candidate, byDdl),
              table == null ? null : writeLoadByTable.get(table)));
    }
    out.sort(
        Comparator.comparing((IndexAdvice a) -> !a.actionable())
            .thenComparing(Comparator.comparingDouble(IndexAdvice::estimatedMsSaved).reversed())
            .thenComparing(IndexAdvice::ddl));
    return out;
  }

  /**
   * The widest other validated index that covers this one by prefix AND was validated against every
   * query this one serves — or null (still needed).
   */
  private static String redundantWith(
      String ddl, Optional<IndexCandidate> self, Map<String, List<ValidatedRow>> byDdl) {
    if (self.isEmpty()) {
      return null;
    }
    Set<Long> mine = queries(byDdl.get(ddl));
    String best = null;
    int bestWidth = -1;
    for (var other : byDdl.entrySet()) {
      if (other.getKey().equals(ddl)) {
        continue;
      }
      Optional<IndexCandidate> wider = IndexCandidate.parseDdl(other.getKey());
      if (wider.isEmpty() || !wider.get().covers(self.get())) {
        continue;
      }
      if (queries(other.getValue()).containsAll(mine) && wider.get().columns().size() > bestWidth) {
        best = other.getKey();
        bestWidth = wider.get().columns().size();
      }
    }
    return best;
  }

  private static Set<Long> queries(List<ValidatedRow> rows) {
    return rows.stream().map(ValidatedRow::queryId).collect(Collectors.toSet());
  }
}
