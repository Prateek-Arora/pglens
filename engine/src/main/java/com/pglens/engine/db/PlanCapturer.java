package com.pglens.engine.db;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Captures the generic plan — {@code EXPLAIN (GENERIC_PLAN, VERBOSE, FORMAT JSON)} — for a
 * normalized statement. GENERIC_PLAN (PG16+) plans the {@code $1}-parameterized text without
 * executing it; VERBOSE qualifies columns (needed by the rules). Statements that cannot be planned
 * generically come back empty (skip + mark), never crash.
 */
public class PlanCapturer {

  // pg_stat_statements normalizes a typed literal `TYPE 'text'` to `TYPE $N`, which is a syntax
  // error to EXPLAIN (e.g. `interval $1`). Rewrite it to the equivalent cast `$N::TYPE`, which
  // parses and plans identically. Longer/multi-word type names precede their prefixes. See
  // ADR-0021.
  private static final Pattern TYPED_LITERAL_PARAM =
      Pattern.compile(
          "\\b(timestamp with(?:out)? time zone|time with(?:out)? time zone|double precision"
              + "|bit varying|timestamptz|timestamp|interval|timetz|time|date|numeric|decimal"
              + "|jsonb|json|uuid|inet|cidr|macaddr|boolean|bytea|money|bit)"
              + "\\s+\\$(\\d+)\\b",
          Pattern.CASE_INSENSITIVE);

  private final JdbcTemplate jdbc;

  public PlanCapturer(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The plan JSON for {@code normalizedSql}, or empty if it cannot be safely explained. */
  public Optional<String> captureGenericPlanJson(String normalizedSql) {
    String explain =
        "EXPLAIN (GENERIC_PLAN, VERBOSE, FORMAT JSON) " + normalizeTypedLiterals(normalizedSql);
    try {
      return Optional.ofNullable(jdbc.queryForObject(explain, String.class));
    } catch (DataAccessException cannotExplain) {
      return Optional.empty();
    }
  }

  /**
   * Rewrites {@code TYPE $N} typed-literal remnants to {@code $N::TYPE} so EXPLAIN can parse them.
   */
  static String normalizeTypedLiterals(String sql) {
    return sql == null ? null : TYPED_LITERAL_PARAM.matcher(sql).replaceAll("\\$$2::$1");
  }
}
