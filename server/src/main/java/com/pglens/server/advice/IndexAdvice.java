package com.pglens.server.advice;

import com.pglens.engine.model.TableWriteLoad;
import java.util.List;

/**
 * One index PgLens advises creating, across every query it was HypoPG-validated for (ADR-0038) —
 * the read model the Phase-4 dashboard/API will serve. {@code estimatedMsSaved} sums the per-query
 * estimates, which is honest only because each one was validated against <em>this exact</em> index
 * (never summed across different indexes). {@code redundantWith} names a wider validated index that
 * was also validated against every one of this index's queries — then this one is not needed.
 * {@code buildCaution} warns that a key value may be too wide for {@code CREATE INDEX} to succeed
 * (B17, ADR-0041). Every number is a labeled planner estimate except the write-load counts, which
 * are real.
 */
public record IndexAdvice(
    String ddl,
    String table,
    String accessMethod,
    double estimatedMsSaved,
    List<QueryEvidence> queries,
    String footprintLabel,
    String buildCaution,
    String redundantWith,
    TableWriteLoad writeLoad) {

  public IndexAdvice {
    queries = List.copyOf(queries);
  }

  public boolean actionable() {
    return redundantWith == null;
  }

  /** This index's validated evidence for one query. */
  public record QueryEvidence(
      long queryId,
      double estimatedMsSaved,
      String scoreBasis,
      double relativeDrop,
      String rangeLabel) {}
}
