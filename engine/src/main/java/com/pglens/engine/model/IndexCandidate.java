package com.pglens.engine.model;

import java.util.List;

/**
 * A proposed index derived from one or more {@link Finding}s: the table, the ordered key columns,
 * the access method, and whether HypoPG can planner-validate it. A candidate is a <em>proposal</em>
 * — HypoPG validation (step 8) decides whether it becomes a recommendation or is suppressed. Pure
 * model — no I/O.
 *
 * <p>Phase 1 generates only full (non-partial) candidates: {@code pg_stat_statements} text is
 * normalized ({@code $1}), so there is no literal to build a partial {@code WHERE} from.
 */
public record IndexCandidate(
    String table,
    List<String> columns,
    AccessMethod accessMethod,
    boolean plannerValidatable,
    String notValidatableReason,
    List<String> sourceRuleIds,
    String rationale) {

  public IndexCandidate {
    columns = columns == null ? List.of() : List.copyOf(columns);
    sourceRuleIds = sourceRuleIds == null ? List.of() : List.copyOf(sourceRuleIds);
  }

  /**
   * Builds a candidate, deriving planner-validatability from the access method (GIN/GiST → not
   * planner-validatable, labeled rather than dropped).
   */
  public static IndexCandidate of(
      String table,
      List<String> columns,
      AccessMethod accessMethod,
      List<String> sourceRuleIds,
      String rationale) {
    boolean validatable = accessMethod.hypoPgSupported();
    String reason =
        validatable
            ? null
            : "HypoPG cannot simulate a "
                + accessMethod.name()
                + " index — surfaced but not planner-validated.";
    return new IndexCandidate(
        table, columns, accessMethod, validatable, reason, sourceRuleIds, rationale);
  }

  /** A deterministic index name for the rendered DDL (HypoPG assigns its own name internally). */
  public String suggestedName() {
    return "idx_" + table + "_" + String.join("_", columns);
  }

  /** The copy-pasteable {@code CREATE INDEX} statement (also what HypoPG is asked to simulate). */
  public String ddl() {
    String using =
        accessMethod == AccessMethod.BTREE ? "" : "USING " + accessMethod.sqlUsing() + " ";
    return "CREATE INDEX %s ON %s %s(%s);"
        .formatted(suggestedName(), table, using, String.join(", ", columns));
  }
}
