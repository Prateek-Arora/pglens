package com.pglens.engine.estimate;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.model.PlanNode;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * Pure tests for locating the {@code $N} an equality predicate binds — the only shape PgLens swaps
 * real values into (ADR-0038). Expression strings are verbatim EXPLAIN VERBOSE renderings.
 */
class EqualityParameterLocatorTest {

  @Test
  void findsTheParameterOfAPlainEquality() {
    assertThat(at("(o.customer_id = $1)", "o", "customer_id")).hasValue(1);
  }

  @Test
  void findsItAmongOtherConjunctsAndWithCasts() {
    String filter = "((o.status = ($2)::text) AND (o.customer_id = $3))";
    assertThat(at(filter, "o", "status")).hasValue(2);
    assertThat(at(filter, "o", "customer_id")).hasValue(3);
    assertThat(at("(c.email = ($1)::character varying)", "c", "email")).hasValue(1);
  }

  @Test
  void findsAFlippedEquality() {
    assertThat(at("($4 = o.customer_id)", "o", "customer_id")).hasValue(4);
  }

  @Test
  void ignoresRangesExpressionsJoinsAndOtherColumns() {
    assertThat(at("(o.created_at >= $1)", "o", "created_at")).isEmpty();
    assertThat(at("(o.created_at >= (now() - ($1)::interval))", "o", "created_at")).isEmpty();
    assertThat(at("(o.total_cents = ($1 * 2))", "o", "total_cents")).isEmpty();
    assertThat(at("(o.customer_id = c.id)", "o", "customer_id")).isEmpty();
    assertThat(at("(o.customer_id = ANY ($1))", "o", "customer_id")).isEmpty();
    assertThat(at("(o.customer_id = $1)", "o", "status")).isEmpty();
    assertThat(at("(x.customer_id = $1)", "o", "customer_id")).isEmpty();
  }

  @Test
  void doesNotConfuseDollarOneWithDollarTen() {
    assertThat(at("(o.customer_id = $10)", "o", "customer_id")).hasValue(10);
  }

  @Test
  void locatesThroughThePlanTreeByRelationNameAndAlias() {
    PlanNode scan = node("Seq Scan", "orders", "o", "(o.customer_id = $1)");
    PlanNode other = node("Seq Scan", "customers", "c", "(c.id = $2)");
    PlanNode root =
        new PlanNode(
            "Hash Join",
            false,
            0,
            100,
            10,
            8,
            null,
            null,
            null,
            "Inner",
            null,
            null,
            null,
            "(o.customer_id = c.id)",
            List.of(),
            List.of(),
            null,
            null,
            null,
            null,
            List.of(scan, other));

    assertThat(EqualityParameterLocator.locate(root, "orders", "customer_id")).hasValue(1);
    assertThat(EqualityParameterLocator.locate(root, "ORDERS", "CUSTOMER_ID")).hasValue(1);
    assertThat(EqualityParameterLocator.locate(root, "customers", "id")).hasValue(2);
    assertThat(EqualityParameterLocator.locate(root, "order_items", "order_id")).isEmpty();
    assertThat(EqualityParameterLocator.locate(null, "orders", "customer_id")).isEmpty();
  }

  @Test
  void enumeratesEveryEqualityBindingOncePerParameter() {
    PlanNode orders = node("Seq Scan", "orders", "o", "((o.customer_id = $1) AND (o.status = $2))");
    PlanNode items = node("Seq Scan", "order_items", "oi", "(oi.quantity >= $3)");
    PlanNode again = node("Index Scan", "orders", "o2", "(o2.customer_id = $1)");
    PlanNode root =
        new PlanNode(
            "Hash Join",
            false,
            0,
            100,
            10,
            8,
            null,
            null,
            null,
            "Inner",
            null,
            null,
            null,
            "(oi.order_id = o.id)",
            List.of(),
            List.of(),
            null,
            null,
            null,
            null,
            List.of(orders, items, again));

    assertThat(EqualityParameterLocator.all(root))
        .containsExactly(
            new EqualityParameterLocator.Binding("orders", "customer_id", 1),
            new EqualityParameterLocator.Binding("orders", "status", 2));
    assertThat(EqualityParameterLocator.all(null)).isEmpty();
  }

  private static OptionalInt at(String expr, String qualifier, String column) {
    return EqualityParameterLocator.inExpression(expr, qualifier, column);
  }

  private static PlanNode node(String type, String relation, String alias, String filter) {
    return new PlanNode(
        type, false, 0, 100, 10, 8, relation, alias, null, null, filter, null, null, null,
        List.of(), List.of(), null, null, null, null, List.of());
  }
}
