package com.pglens.engine.confirm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pglens.engine.model.AccessMethod;
import java.util.List;

/**
 * One recommended index, built for real on the copy and measured (ADR-0042). The index-level {@code
 * verdict} is over all its queries' statements together ({@code 1 − Σafter / Σbefore}); each query
 * also has its own, since an index can speed up most of its queries and slow down a few. {@code
 * reason} explains an UNBUILDABLE (the {@code CREATE INDEX} error message, without its detail) or
 * NOT_MEASURED verdict. In a dry run nothing is built and {@code verdict} is null. Pure model.
 */
public record IndexConfirmation(
    int rank,
    String ddl,
    String table,
    List<String> columns,
    AccessMethod accessMethod,
    Verdict verdict,
    Double measuredDrop,
    Double beforeMs,
    Double afterMs,
    boolean afterAtLeast,
    Long buildMs,
    Long indexBytes,
    String reason,
    String buildCaution,
    List<QueryConfirmation> queries) {

  public IndexConfirmation {
    columns = columns == null ? List.of() : List.copyOf(columns);
    queries = queries == null ? List.of() : List.copyOf(queries);
  }

  /** How many of this index's queries it measurably slowed down. */
  @JsonProperty("slowerQueries") // derived, but part of the confirm 1.0 contract
  public long slowerQueries() {
    return queries.stream().filter(q -> q.verdict() == Verdict.SLOWER).count();
  }

  /** How many of this index's queries it measurably sped up. */
  @JsonProperty("fasterQueries")
  public long fasterQueries() {
    return queries.stream().filter(q -> q.verdict() == Verdict.FASTER).count();
  }
}
