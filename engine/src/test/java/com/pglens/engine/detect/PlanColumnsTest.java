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
}
