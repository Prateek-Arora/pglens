package com.pglens.engine.rank;

import com.pglens.engine.model.IndexCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which "does the more general index also serve this query?" validations are still missing
 * (ADR-0038). For every pair of planner-validated indexes where one strictly covers the other by
 * btree prefix (same table + access method, different DDL), each query validated for the smaller
 * index should also be validated against the larger one — only a pass makes the smaller redundant.
 * The server enqueues these as ordinary {@code (queryid, ddl)} validation jobs; the CLI runs them
 * in-session. Pure — no I/O.
 */
public final class CoverageChecks {

  private CoverageChecks() {}

  /** One check to run: validate {@code ddl} against {@code queryId}'s statement. */
  public record Check(long queryId, String ddl, String accessMethod) {}

  /** A (query, index) pair that already has a verdict of any status. */
  public record Verdict(long queryId, String ddl) {}

  /**
   * @param validatedQueriesByDdl each planner-validated index DDL → the queries it was validated
   *     for
   * @param existing every (query, DDL) pair that already has a verdict (validated or not) — those
   *     are never re-requested here (the revalidate cooldown refreshes them)
   */
  public static List<Check> missing(
      Map<String, Set<Long>> validatedQueriesByDdl, Set<Verdict> existing) {
    List<Check> out = new ArrayList<>();
    for (var general : validatedQueriesByDdl.entrySet()) {
      Optional<IndexCandidate> g = IndexCandidate.parseDdl(general.getKey());
      if (g.isEmpty()) {
        continue;
      }
      for (var specific : validatedQueriesByDdl.entrySet()) {
        if (specific.getKey().equals(general.getKey())) {
          continue;
        }
        Optional<IndexCandidate> s = IndexCandidate.parseDdl(specific.getKey());
        if (s.isEmpty() || !g.get().covers(s.get())) {
          continue;
        }
        for (long queryId : specific.getValue()) {
          if (!existing.contains(new Verdict(queryId, general.getKey()))) {
            out.add(new Check(queryId, general.getKey(), g.get().accessMethod().name()));
          }
        }
      }
    }
    return out;
  }
}
