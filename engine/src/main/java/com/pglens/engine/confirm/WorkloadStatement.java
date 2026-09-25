package com.pglens.engine.confirm;

import java.util.List;

/**
 * One real statement from the user's workload, to be replayed on a scratch copy (Phase 2.6). {@code
 * sql} is the text as the application sent it; {@code parameters} are the values bound to its
 * {@code $N} placeholders, already written as SQL literals exactly as PostgreSQL logged them
 * ({@code '2'}, {@code 'it''s'}, {@code NULL}), empty for a statement with its values inline.
 * {@code origin} says where it came from ({@code queries.sql #3}, {@code postgresql.log:120}).
 *
 * <p>These carry real user values: they stay in memory on the user's machine and never go into a
 * report, the JSON, or the wire (ADR-0042). Pure model — no I/O.
 */
public record WorkloadStatement(String sql, List<String> parameters, String origin) {

  public WorkloadStatement {
    parameters = parameters == null ? List.of() : List.copyOf(parameters);
  }

  /** True if the statement has {@code $N} placeholders to bind (replayed via PREPARE/EXECUTE). */
  public boolean hasParameters() {
    return !parameters.isEmpty();
  }
}
