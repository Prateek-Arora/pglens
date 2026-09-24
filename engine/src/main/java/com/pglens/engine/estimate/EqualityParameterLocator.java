package com.pglens.engine.estimate;

import com.pglens.engine.model.PlanNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the bound parameters ({@code $N}) a generic plan compares table columns to by
 * <b>equality</b> — e.g. {@code (o.customer_id = $1)} on the node scanning {@code orders AS o} →
 * {@code orders.customer_id ↔ $1}. Those are the parameters PgLens swaps for real sampled values to
 * see how an index's win varies with the values a query uses (ADR-0038). All of a query's equality
 * parameters matter, not only the index's own column: for a join index such as {@code
 * order_items(order_id)}, the rows it must fetch are decided by {@code orders.customer_id = $1}.
 *
 * <p>Deliberately narrow: only a bare parameter (optionally parenthesized and/or cast, e.g. {@code
 * ($1)::text}) on either side of {@code =}. Anything else — ranges, expressions like {@code $1 +
 * 1}, {@code = ANY(...)} — is not located, so the caller keeps the generic estimate rather than
 * guess. EXPLAIN VERBOSE qualifies every column with its alias, which is what makes a regex
 * reliable here. Pure — no I/O.
 */
public final class EqualityParameterLocator {

  private EqualityParameterLocator() {}

  private static final String IDENT = "[A-Za-z_][A-Za-z0-9_]*";

  // A bare, optionally parenthesized/cast parameter: $1 · ($1) · ($1)::text · $1::character varying
  private static final String PARAM =
      "\\(*\\$(\\d+)(?:\\)*::" + IDENT + "(?: " + IDENT + ")*(?:\\[\\])?)*\\)*";

  // What may follow the parameter: end of the condition, a closing paren, or a boolean connective.
  private static final String END = "(?=\\s*(?:\\)|$|\\s(?:AND|OR)\\s))";

  // q.col = <param>
  private static final Pattern COLUMN_EQ_PARAM =
      Pattern.compile("(" + IDENT + ")\\.(" + IDENT + ")\\s*=\\s*" + PARAM + END);

  // <param> = q.col  (the planner occasionally flips the operands)
  private static final Pattern PARAM_EQ_COLUMN =
      Pattern.compile("(?:^|[\\s(])" + PARAM + "\\s*=\\s*(" + IDENT + ")\\.(" + IDENT + ")" + END);

  /** One equality binding: {@code table.column = $param}. */
  public record Binding(String table, String column, int param) {}

  /**
   * Every equality binding in {@code plan}, one per parameter number, in plan order (the first
   * binding found for a parameter wins). Empty for a null plan.
   */
  public static List<Binding> all(PlanNode plan) {
    List<Binding> out = new ArrayList<>();
    if (plan == null) {
      return out;
    }
    Set<Integer> seen = new HashSet<>();
    for (PlanNode node : plan.flatten()) {
      if (node.relationName() == null) {
        continue;
      }
      String qualifier = qualifierOf(node);
      for (String expr : new String[] {node.filter(), node.indexCond(), node.recheckCond()}) {
        if (expr == null) {
          continue;
        }
        Matcher m = COLUMN_EQ_PARAM.matcher(expr);
        while (m.find()) {
          int param = Integer.parseInt(m.group(3));
          if (m.group(1).equalsIgnoreCase(qualifier) && seen.add(param)) {
            out.add(new Binding(node.relationName(), m.group(2), param));
          }
        }
        Matcher flipped = PARAM_EQ_COLUMN.matcher(expr);
        while (flipped.find()) {
          int param = Integer.parseInt(flipped.group(1));
          if (flipped.group(2).equalsIgnoreCase(qualifier) && seen.add(param)) {
            out.add(new Binding(node.relationName(), flipped.group(3), param));
          }
        }
      }
    }
    return out;
  }

  /**
   * The parameter number compared by equality to {@code table.column} anywhere in {@code plan}
   * (Filter, Index Cond, or Recheck Cond of a node scanning {@code table}), or empty.
   */
  public static OptionalInt locate(PlanNode plan, String table, String column) {
    if (plan == null || table == null || column == null) {
      return OptionalInt.empty();
    }
    for (PlanNode node : plan.flatten()) {
      if (node.relationName() == null || !node.relationName().equalsIgnoreCase(table)) {
        continue;
      }
      for (String expr : new String[] {node.filter(), node.indexCond(), node.recheckCond()}) {
        OptionalInt found = inExpression(expr, qualifierOf(node), column);
        if (found.isPresent()) {
          return found;
        }
      }
    }
    return OptionalInt.empty();
  }

  /** The parameter compared by equality to {@code qualifier.column} in one expression, or empty. */
  static OptionalInt inExpression(String expr, String qualifier, String column) {
    if (expr == null) {
      return OptionalInt.empty();
    }
    Matcher m = COLUMN_EQ_PARAM.matcher(expr);
    while (m.find()) {
      if (m.group(1).equalsIgnoreCase(qualifier) && m.group(2).equalsIgnoreCase(column)) {
        return OptionalInt.of(Integer.parseInt(m.group(3)));
      }
    }
    Matcher flipped = PARAM_EQ_COLUMN.matcher(expr);
    while (flipped.find()) {
      if (flipped.group(2).equalsIgnoreCase(qualifier)
          && flipped.group(3).equalsIgnoreCase(column)) {
        return OptionalInt.of(Integer.parseInt(flipped.group(1)));
      }
    }
    return OptionalInt.empty();
  }

  private static String qualifierOf(PlanNode node) {
    return node.alias() != null ? node.alias() : node.relationName();
  }
}
