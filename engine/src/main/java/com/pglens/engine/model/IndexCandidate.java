package com.pglens.engine.model;

import java.util.ArrayList;
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
 * <p>{@code table} is the table's identity ({@link SqlIdent#table}) and {@code columns} are raw
 * names; {@link #ddl()} quotes them the way Postgres would (ADR-0049).
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

  // Inverse of ddl(): "CREATE INDEX <name> ON <table> [USING <am> ](<c1>, <c2>);" — identifiers as
  // SqlIdent.quote writes them (older rows hold bare lower-case names, which parse the same way).
  private static final String IDENT = "(?:\"(?:[^\"]|\"\")+\"|[^\\s.\"(),;]+)";
  private static final Pattern DDL =
      Pattern.compile(
          "^\\s*CREATE INDEX \\S+ ON ("
              + IDENT
              + "(?:\\."
              + IDENT
              + ")?) (?:USING (\\w+) )?\\((.+)\\);?\\s*$",
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
    List<String> tableParts = SqlIdent.parts(m.group(1));
    List<String> columns = new ArrayList<>();
    for (String token : splitTopLevelCommas(m.group(3))) {
      if (!SqlIdent.isIdentifier(token)) {
        return Optional.empty();
      }
      columns.add(SqlIdent.unquote(token));
    }
    if (tableParts.isEmpty() || tableParts.size() > 2 || columns.isEmpty()) {
      return Optional.empty();
    }
    String table =
        tableParts.size() == 2
            ? SqlIdent.table(tableParts.get(0), tableParts.get(1))
            : SqlIdent.table(null, tableParts.get(0));
    return Optional.of(of(table, columns, method, List.of(), null));
  }

  /** {@code a, "b,c"} → {@code [a, "b,c"]}: commas inside double quotes don't split. */
  private static List<String> splitTopLevelCommas(String list) {
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quoted = false;
    for (char c : list.toCharArray()) {
      if (c == '"') {
        quoted = !quoted;
      }
      if (c == ',' && !quoted) {
        out.add(current.toString().strip());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    out.add(current.toString().strip());
    return out;
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

  /**
   * True if {@code existing} — an index already on the database — serves this candidate: same table
   * and access method, not partial, and its key starts with this candidate's columns. This is how
   * PgLens recognises that a recommendation was applied, whatever the index is called (ADR-0051).
   */
  public boolean servedBy(IndexInfo existing) {
    if (existing == null
        || existing.isPartial()
        || !table.equalsIgnoreCase(existing.table())
        || !accessMethod.sqlUsing().equalsIgnoreCase(String.valueOf(existing.method()))
        || columns.size() > existing.columns().size()) {
      return false;
    }
    for (int i = 0; i < columns.size(); i++) {
      if (!columns.get(i).equalsIgnoreCase(existing.columns().get(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * A deterministic index name for the rendered DDL (HypoPG assigns its own name internally):
   * {@code idx_<table>_<columns>}, lower case with anything but letters, digits and {@code _}
   * turned into {@code _}, so it never needs quoting; a name past Postgres's 63-byte limit is cut
   * and ends in a hash of the full name, so two long names can't collide.
   */
  public String suggestedName() {
    String raw = "idx_" + SqlIdent.relationName(table) + "_" + String.join("_", columns);
    String name = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    if (name.length() <= SqlIdent.MAX_IDENTIFIER_BYTES) {
      return name;
    }
    String hash = Integer.toHexString(raw.hashCode() & 0x7fffffff);
    return name.substring(0, SqlIdent.MAX_IDENTIFIER_BYTES - hash.length() - 1) + "_" + hash;
  }

  /** The copy-pasteable {@code CREATE INDEX} statement (also what HypoPG is asked to simulate). */
  public String ddl() {
    String using =
        accessMethod == AccessMethod.BTREE ? "" : "USING " + accessMethod.sqlUsing() + " ";
    return "CREATE INDEX %s ON %s %s(%s);"
        .formatted(
            suggestedName(),
            table,
            using,
            String.join(", ", columns.stream().map(SqlIdent::quote).toList()));
  }
}
