package com.pglens.engine.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helpers that pull column references out of EXPLAIN expression strings. EXPLAIN VERBOSE
 * qualifies every column with its table or alias (e.g. {@code (o.customer_id = $1)}), which is what
 * makes this reliable without a SQL parser.
 */
final class PlanColumns {

  private PlanColumns() {}

  // qualifier.column <op>  — the right-hand side (a bound parameter, an expression, or a join
  // column) is inspected separately, so `col = $1` and `col >= now() - $1` both qualify but the
  // join `a.x = b.y` does not.
  private static final Pattern COLUMN_COMPARISON =
      Pattern.compile(
          "([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|<>|!=|<=|>=|<|>)\\s*");

  // A right operand that starts with another qualified column is a join, not an indexable filter.
  private static final Pattern RHS_IS_COLUMN =
      Pattern.compile("^\\(*[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*");

  private static final Pattern BOUND_PARAM = Pattern.compile("\\$\\d+");
  private static final Pattern CONNECTIVE =
      Pattern.compile("\\s+(?:AND|OR)\\s+", Pattern.CASE_INSENSITIVE);

  // qualifier.column = qualifier.column  — an equi-join condition.
  private static final Pattern JOIN =
      Pattern.compile(
          "([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)"
              + "\\s*=\\s*"
              + "([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)");

  // (qualifier.)?column <gin-op>  — a containment/existence predicate a GIN index can serve.
  // Longer operators must precede their prefixes in the alternation (?| / ?& before ?).
  private static final Pattern JSONB_PREDICATE =
      Pattern.compile(
          "(?:([A-Za-z_][A-Za-z0-9_]*)\\.)?([A-Za-z_][A-Za-z0-9_]*)"
              + "\\s*(@>|@\\?|@@|\\?\\||\\?&|\\?)");

  /**
   * Columns compared against a bound parameter ($N) in a Filter/Index Cond — directly ({@code col =
   * $1}) or through an expression ({@code col >= now() - $1}) — in appearance order. A column
   * compared to another column ({@code a.x = b.y}) is a join, not an indexable filter, and is left
   * to the join rule.
   */
  static List<QualifiedColumn> predicateColumns(String expr) {
    List<QualifiedColumn> out = new ArrayList<>();
    if (expr == null) {
      return out;
    }
    Matcher m = COLUMN_COMPARISON.matcher(expr);
    while (m.find()) {
      String rhs = rightOperand(expr, m.end());
      if (!RHS_IS_COLUMN.matcher(rhs).find() && BOUND_PARAM.matcher(rhs).find()) {
        out.add(new QualifiedColumn(m.group(1), m.group(2)));
      }
    }
    return out;
  }

  /** The comparison's right operand: from {@code start} up to the next top-level AND/OR, or end. */
  private static String rightOperand(String expr, int start) {
    Matcher connective = CONNECTIVE.matcher(expr);
    return connective.find(start)
        ? expr.substring(start, connective.start())
        : expr.substring(start);
  }

  /** Both sides of every {@code a.x = b.y} equi-join condition in the expression. */
  static List<QualifiedColumn> joinColumns(String cond) {
    List<QualifiedColumn> out = new ArrayList<>();
    if (cond == null) {
      return out;
    }
    Matcher m = JOIN.matcher(cond);
    while (m.find()) {
      out.add(new QualifiedColumn(m.group(1), m.group(2)));
      out.add(new QualifiedColumn(m.group(3), m.group(4)));
    }
    return out;
  }

  /**
   * Columns filtered by a GIN-servable containment/existence operator ({@code @>}, {@code ?},
   * {@code ?|}, {@code ?&}, {@code @?}, {@code @@}), with the operator, in appearance order. The
   * column may be unqualified (single-table scans sometimes render it without a prefix).
   */
  static List<JsonbPredicate> jsonbPredicates(String expr) {
    List<JsonbPredicate> out = new ArrayList<>();
    if (expr == null) {
      return out;
    }
    Matcher m = JSONB_PREDICATE.matcher(expr);
    while (m.find()) {
      out.add(new JsonbPredicate(new QualifiedColumn(m.group(1), m.group(2)), m.group(3)));
    }
    return out;
  }

  /** The column of a sort key like {@code "o.created_at DESC"} (ASC/DESC/NULLS suffix stripped). */
  static QualifiedColumn sortColumn(String sortKey) {
    if (sortKey == null || sortKey.isBlank()) {
      return null;
    }
    String head = sortKey.trim().split("\\s+")[0];
    int dot = head.indexOf('.');
    return dot > 0
        ? new QualifiedColumn(head.substring(0, dot), head.substring(dot + 1))
        : new QualifiedColumn(null, head);
  }

  /** A column reference split into its qualifier (table or alias) and column name. */
  record QualifiedColumn(String qualifier, String column) {}

  /** A column filtered by a GIN-servable operator, paired with that operator's text. */
  record JsonbPredicate(QualifiedColumn column, String operator) {}
}
