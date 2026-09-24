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

  // pg_stat_statements normalizes the field of `extract(year FROM x)` (parsed as a string constant)
  // to `$N`, and `extract($N FROM x)` is a syntax error. `date_part($N, x)` is the same function
  // with the field as an ordinary argument, so it plans identically (ADR-0038, found by the TPC-H
  // accuracy benchmark).
  private static final Pattern EXTRACT_PARAM =
      Pattern.compile("\\bextract\\s*\\(\\s*\\$(\\d+)\\s+from\\s+", Pattern.CASE_INSENSITIVE);

  // Literal arithmetic such as `l_quantity <= 10 + 10` normalizes to `$8 + $9`: two untyped
  // parameters, for which Postgres can't choose an operator ("operator is not unique: unknown +
  // unknown"). Typing both as numeric lets it plan; a wrong guess just fails to plan again (the
  // statement is then reported as not captured), so it can never produce a number on its own.
  private static final Pattern UNTYPED_PARAM_ARITHMETIC =
      Pattern.compile("\\$(\\d+)(?![\\d:])(\\s*[-+*/]\\s*)\\$(\\d+)(?![\\d:])");

  private final JdbcTemplate jdbc;

  public PlanCapturer(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The plan JSON for {@code normalizedSql}, or empty if it cannot be safely explained. */
  public Optional<String> captureGenericPlanJson(String normalizedSql) {
    String explain =
        "EXPLAIN (GENERIC_PLAN, VERBOSE, FORMAT JSON) " + normalizeForExplain(normalizedSql);
    try {
      return Optional.ofNullable(jdbc.queryForObject(explain, String.class));
    } catch (DataAccessException cannotExplain) {
      return Optional.empty();
    }
  }

  /**
   * Rewrites the shapes pg_stat_statements normalization leaves unplannable into equivalent
   * plannable ones: typed-literal remnants, {@code extract($N FROM x)}, and untyped {@code $a ± $b}
   * arithmetic. Each rewrite is covered by the capture-robustness pack.
   */
  static String normalizeForExplain(String sql) {
    if (sql == null) {
      return null;
    }
    String out = normalizeTypedLiterals(sql);
    out = EXTRACT_PARAM.matcher(out).replaceAll("date_part(\\$$1, ");
    return UNTYPED_PARAM_ARITHMETIC.matcher(out).replaceAll("\\$$1::numeric$2\\$$3::numeric");
  }

  /**
   * Rewrites {@code TYPE $N} typed-literal remnants to {@code $N::TYPE} so EXPLAIN can parse them.
   */
  static String normalizeTypedLiterals(String sql) {
    return sql == null ? null : TYPED_LITERAL_PARAM.matcher(sql).replaceAll("\\$$2::$1");
  }
}
