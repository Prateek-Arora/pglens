package com.pglens.engine.detect;

import com.pglens.engine.model.SqlIdent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helpers that pull column references out of EXPLAIN expression strings. EXPLAIN VERBOSE
 * qualifies every column with its table or alias (e.g. {@code (o.customer_id = $1)}), which is what
 * makes this reliable without a SQL parser. Identifiers come as Postgres writes them — bare, or
 * double-quoted when they need it ({@code "Post"."authorId"}) — and are returned raw (unquoted,
 * case preserved; ADR-0049). A {@code varchar} or {@code char} column is compared through a relabel
 * cast ({@code (u.status)::text = $1}), which a B-tree on the column still serves.
 */
final class PlanColumns {

  private PlanColumns() {}

  // One identifier as EXPLAIN prints it: "quoted" (with "" escapes) or bare.
  private static final String IDENT =
      "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_\\u0080-\\uffff][A-Za-z0-9_$\\u0080-\\uffff]*)";

  // qualifier.column, either bare or as a text relabel cast: (qualifier.column)::text. A function
  // call such as lower(q.c) = $1 matches neither (it needs an expression index — backlog B3).
  private static final String COLUMN_OPERAND =
      "(?:\\(("
          + IDENT
          + ")\\.("
          + IDENT
          + ")\\)::(?:text|bpchar)|("
          + IDENT
          + ")\\.("
          + IDENT
          + "))";

  // <column operand> <op>  — the right-hand side (a bound parameter, an expression, or a join
  // column) is inspected separately, so `col = $1` and `col >= now() - $1` both qualify but the
  // join `a.x = b.y` does not.
  private static final Pattern COLUMN_COMPARISON =
      Pattern.compile(COLUMN_OPERAND + "\\s*(?:=|<>|!=|<=|>=|<|>)\\s*");

  // A right operand that starts with another qualified column is a join, not an indexable filter.
  private static final Pattern RHS_IS_COLUMN =
      Pattern.compile("^\\(*" + IDENT + "\\.(?:" + IDENT + ")");

  private static final Pattern BOUND_PARAM = Pattern.compile("\\$\\d+");
  private static final Pattern CONNECTIVE =
      Pattern.compile("\\s+(?:AND|OR)\\s+", Pattern.CASE_INSENSITIVE);

  // qualifier.column = qualifier.column  — an equi-join condition (either side may be cast).
  private static final Pattern JOIN =
      Pattern.compile(COLUMN_OPERAND + "\\s*=\\s*" + COLUMN_OPERAND);

  // (qualifier.)?column <gin-op>  — a containment/existence predicate a GIN index can serve.
  // Longer operators must precede their prefixes in the alternation (?| / ?& before ?).
  private static final Pattern JSONB_PREDICATE =
      Pattern.compile(
          "(?:(" + IDENT + ")\\.)?(" + IDENT + ")" + "\\s*(@>|@\\?|@@|\\?\\||\\?&|\\?)");

  // A sort key: an optionally qualified column, optionally cast, then an optional direction.
  private static final Pattern SORT_KEY =
      Pattern.compile(
          "^\\(?(?:("
              + IDENT
              + ")\\.)?("
              + IDENT
              + ")\\)?(?:::[A-Za-z_ ]+?)?"
              + "(?:\\s+(?:ASC|DESC))?(?:\\s+NULLS\\s+(?:FIRST|LAST))?$",
          Pattern.CASE_INSENSITIVE);

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
        out.add(operand(m, 0));
      }
    }
    return out;
  }

  /** The column of the {@link #COLUMN_OPERAND} starting at group {@code offset + 1}, unquoted. */
  private static QualifiedColumn operand(Matcher m, int offset) {
    boolean cast = m.group(offset + 1) != null;
    return QualifiedColumn.of(m.group(offset + (cast ? 1 : 3)), m.group(offset + (cast ? 2 : 4)));
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
      out.add(operand(m, 0));
      out.add(operand(m, 4));
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
      out.add(new JsonbPredicate(QualifiedColumn.of(m.group(1), m.group(2)), m.group(3)));
    }
    return out;
  }

  /**
   * The column of a sort key like {@code "o.created_at DESC"} or {@code p."createdAt" DESC NULLS
   * LAST} (direction stripped); null for an expression key such as {@code lower(u.email)}.
   */
  static QualifiedColumn sortColumn(String sortKey) {
    if (sortKey == null || sortKey.isBlank()) {
      return null;
    }
    Matcher m = SORT_KEY.matcher(sortKey.strip());
    return m.matches() ? QualifiedColumn.of(m.group(1), m.group(2)) : null;
  }

  /**
   * A column reference split into its qualifier (table or alias) and column name, both raw —
   * unquoted and case preserved ({@code p."authorId"} → {@code p}, {@code authorId}).
   */
  record QualifiedColumn(String qualifier, String column) {

    /** From the identifier tokens as EXPLAIN printed them (the qualifier may be absent). */
    static QualifiedColumn of(String qualifierToken, String columnToken) {
      return new QualifiedColumn(
          qualifierToken == null ? null : SqlIdent.unquote(qualifierToken),
          SqlIdent.unquote(columnToken));
    }
  }

  /** A column filtered by a GIN-servable operator, paired with that operator's text. */
  record JsonbPredicate(QualifiedColumn column, String operator) {}
}
