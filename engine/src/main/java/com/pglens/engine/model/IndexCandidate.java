package com.pglens.engine.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

  // Inverse of ddl(): "CREATE INDEX <name> ON <table> [USING <am> ](<c1>, <c2>);"
  private static final Pattern DDL =
      Pattern.compile(
          "^\\s*CREATE INDEX \\S+ ON (\\S+) (?:USING (\\w+) )?\\((.+)\\);?\\s*$",
          Pattern.CASE_INSENSITIVE);

  /**
   * Parses a DDL string this class rendered ({@link #ddl()}) back into a candidate — the server and
   * the edge validator only carry the DDL text, and need its table / key columns / access method
   * (ADR-0038). Empty for anything not in that exact shape (never guessed).
   */
  public static Optional<IndexCandidate> parseDdl(String ddl) {
    if (ddl == null) {
      return Optional.empty();
    }
    Matcher m = DDL.matcher(ddl);
    if (!m.matches()) {
      return Optional.empty();
    }
    AccessMethod method;
    try {
      method =
          m.group(2) == null
              ? AccessMethod.BTREE
              : AccessMethod.valueOf(m.group(2).toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknownMethod) {
      return Optional.empty();
    }
    List<String> columns = Arrays.stream(m.group(3).split(",")).map(String::strip).toList();
    if (columns.stream().anyMatch(String::isEmpty)) {
      return Optional.empty();
    }
    return Optional.of(of(m.group(1), columns, method, List.of(), null));
  }

  /**
   * True if this index serves {@code specific}'s lookups by btree-prefix: same table and access
   * method, and {@code specific}'s key columns are a leading prefix of this one's (or equal). A
   * structural hint only — whether it actually serves a query is HypoPG's call (ADR-0038).
   */
  public boolean covers(IndexCandidate specific) {
    if (!table.equalsIgnoreCase(specific.table()) || accessMethod != specific.accessMethod()) {
      return false;
    }
    if (specific.columns().size() > columns.size()) {
      return false;
    }
    for (int i = 0; i < specific.columns().size(); i++) {
      if (!columns.get(i).equalsIgnoreCase(specific.columns().get(i))) {
        return false;
      }
    }
    return true;
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
