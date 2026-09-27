package com.pglens.engine.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A schema holding tables the PgLens role can't read: without {@code USAGE} on the schema or {@code
 * SELECT} on a table, Postgres refuses even to plan a query on it, so its plan can't be captured
 * (ADR-0049). {@link #advice} turns the gaps into the grants that fix them. Pure model — no I/O.
 *
 * @param schema the schema's raw name
 * @param unreadable how many of its tables the role can't read
 * @param tables how many tables it has
 * @param usage whether the role has {@code USAGE} on the schema
 */
public record AccessGap(String schema, int unreadable, int tables, boolean usage) {

  /**
   * One sentence naming the gaps and the {@code GRANT}s (to run as the tables' owner) that close
   * them, e.g. {@code GRANT USAGE ON SCHEMA app TO pglens_ro; GRANT SELECT ON ALL TABLES IN SCHEMA
   * app TO pglens_ro;} — or empty when there are none.
   */
  public static String advice(String role, List<AccessGap> gaps) {
    if (gaps == null || gaps.isEmpty()) {
      return "";
    }
    int unreadable = gaps.stream().mapToInt(AccessGap::unreadable).sum();
    int tables = gaps.stream().mapToInt(AccessGap::tables).sum();
    String schemas =
        gaps.stream().map(g -> SqlIdent.quote(g.schema())).collect(Collectors.joining(", "));
    String grantee = SqlIdent.quote(role);
    StringBuilder grants = new StringBuilder();
    List<AccessGap> noUsage = gaps.stream().filter(g -> !g.usage()).toList();
    if (!noUsage.isEmpty()) {
      grants.append(
          "GRANT USAGE ON SCHEMA %s TO %s; "
              .formatted(
                  noUsage.stream()
                      .map(g -> SqlIdent.quote(g.schema()))
                      .collect(Collectors.joining(", ")),
                  grantee));
    }
    grants.append("GRANT SELECT ON ALL TABLES IN SCHEMA %s TO %s;".formatted(schemas, grantee));
    return ("The PgLens role %s can't read %d of the %d tables in schema %s, so plans for queries on"
            + " them can't be captured. As the tables' owner, run: %s (add ALTER DEFAULT PRIVILEGES"
            + " … GRANT SELECT ON TABLES to cover tables created later).")
        .formatted(grantee, unreadable, tables, schemas, grants);
  }
}
