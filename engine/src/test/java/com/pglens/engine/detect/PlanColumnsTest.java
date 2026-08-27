package com.pglens.engine.detect;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.detect.PlanColumns.JsonbPredicate;
import com.pglens.engine.detect.PlanColumns.QualifiedColumn;
import org.junit.jupiter.api.Test;

class PlanColumnsTest {

  @Test
  void extractsAQualifiedPredicateColumn() {
    assertThat(PlanColumns.predicateColumns("(customers.email = $1)"))
        .containsExactly(new QualifiedColumn("customers", "email"));
  }

  @Test
  void extractsEveryParameterizedColumnInOrder() {
    assertThat(PlanColumns.predicateColumns("((o.country = $1) OR (o.total_cents > $2))"))
        .containsExactly(
            new QualifiedColumn("o", "country"), new QualifiedColumn("o", "total_cents"));
  }

  @Test
  void extractsBothSidesOfAJoinCondition() {
    assertThat(PlanColumns.joinColumns("(oi.order_id = o.id)"))
        .containsExactly(new QualifiedColumn("oi", "order_id"), new QualifiedColumn("o", "id"));
  }

  @Test
  void parsesSortKeyStrippingDirection() {
    assertThat(PlanColumns.sortColumn("orders.created_at DESC"))
        .isEqualTo(new QualifiedColumn("orders", "created_at"));
  }

  @Test
  void matchesARangePredicateThroughAnExpression() {
    // The parameter is on the far side of an expression (a normalized `interval '7 days'`); a btree
    // on the column still serves the range, so R1 must see it (ADR-0021).
    assertThat(PlanColumns.predicateColumns("(orders.created_at >= (now() - $1))"))
        .containsExactly(new QualifiedColumn("orders", "created_at"));
  }

  @Test
  void treatsAColumnToColumnComparisonAsAJoinNotAFilter() {
    // A column compared to another column is a join (R3's job), not an indexable filter — even when
    // a parameter sits elsewhere in the same expression.
    assertThat(PlanColumns.predicateColumns("(oi.order_id = o.id)")).isEmpty();
    assertThat(PlanColumns.predicateColumns("((oi.order_id = o.id) AND (o.customer_id = $1))"))
        .containsExactly(new QualifiedColumn("o", "customer_id"));
  }

  @Test
  void extractsAJsonbContainmentColumnWithItsOperator() {
    assertThat(PlanColumns.jsonbPredicates("(events.payload @> $1)"))
        .singleElement()
        .isEqualTo(new JsonbPredicate(new QualifiedColumn("events", "payload"), "@>"));
  }

  @Test
  void distinguishesTheKeyExistsOperatorsWithoutConfusingTheirPrefixes() {
    // ?| and ?& must not be truncated to a bare ? by the alternation.
    assertThat(PlanColumns.jsonbPredicates("((events.payload ?| $1) AND (events.payload ? $2))"))
        .extracting(JsonbPredicate::operator)
        .containsExactly("?|", "?");
  }

  @Test
  void ignoresPlainScalarComparisonsWhenExtractingGinPredicates() {
    assertThat(PlanColumns.jsonbPredicates("(orders.status = $1)")).isEmpty();
  }

  // --- Shape-robustness cases: realistic normalized predicates the neat demo does not cover. ---
  // These pin what column extraction DOES and, deliberately, does NOT handle, so a regression or a
  // future capability is a conscious change. The boundaries below map to backlog items.

  @Test
  void extractsAColumnComparedToAParameterArray() {
    // An IN-list normalizes to `= ANY ($1)`; a btree still serves it, so R1 must see the column.
    assertThat(PlanColumns.predicateColumns("(orders.status = ANY ($1))"))
        .containsExactly(new QualifiedColumn("orders", "status"));
  }

  @Test
  void extractsEveryColumnOfAMultiColumnAndFilter() {
    assertThat(PlanColumns.predicateColumns("((orders.customer_id = $1) AND (orders.status = $2))"))
        .containsExactly(
            new QualifiedColumn("orders", "customer_id"), new QualifiedColumn("orders", "status"));
  }

  @Test
  void extractsBothBoundsOfABetweenRange() {
    // BETWEEN normalizes to a >= / <= pair on one column; both are returned (the Rule dedupes).
    assertThat(
            PlanColumns.predicateColumns(
                "((orders.total_cents >= $1) AND (orders.total_cents <= $2))"))
        .containsExactly(
            new QualifiedColumn("orders", "total_cents"),
            new QualifiedColumn("orders", "total_cents"));
  }

  @Test
  void extractsANotEqualPredicateLiberallyLeavingHypoPgToSuppressIt() {
    // Liberal by design: `<>` can't use a btree, but the rule flags it and HypoPG suppresses the
    // candidate — no fabricated win. Extraction here just proves the column is seen.
    assertThat(PlanColumns.predicateColumns("(orders.status <> $1)"))
        .containsExactly(new QualifiedColumn("orders", "status"));
  }

  @Test
  void doesNotExtractAColumnWrappedInAFunction() {
    // `lower(email) = $1` needs an EXPRESSION index, not a plain btree on the column — extracting
    // `email` here would generate a candidate HypoPG can't match. Handling this is a backlog item
    // (expression-index rule), so today it is deliberately not extracted.
    assertThat(PlanColumns.predicateColumns("(lower(customers.email) = $1)")).isEmpty();
  }

  @Test
  void doesNotExtractAColumnWrappedInACast() {
    // Same reasoning as the function case: `(customer_id)::text = $1` is served by an expression
    // index on the cast, not a btree on customer_id. Deliberately not extracted (backlog).
    assertThat(PlanColumns.predicateColumns("((orders.customer_id)::text = $1)")).isEmpty();
  }

  @Test
  void doesNotTreatAJsonbFieldExtractionAsAnIndexablePlainOrGinPredicate() {
    // `payload ->> 'k' = $2` is neither a plain-column filter (a btree on payload won't serve it)
    // nor a GIN containment/existence op. Both extractors correctly return nothing (backlog:
    // expression index on the extracted field).
    String expr = "((events.payload ->> $1) = $2)";
    assertThat(PlanColumns.predicateColumns(expr)).isEmpty();
    assertThat(PlanColumns.jsonbPredicates(expr)).isEmpty();
  }
}
