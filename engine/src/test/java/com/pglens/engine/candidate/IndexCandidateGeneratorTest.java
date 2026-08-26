package com.pglens.engine.candidate;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.Finding;
import com.pglens.engine.model.Finding.Confidence;
import com.pglens.engine.model.IndexCandidate;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndexCandidateGeneratorTest {

  private final IndexCandidateGenerator generator = new IndexCandidateGenerator();

  @Test
  void mergesFilterAndSortIntoAOneCompositeWithEqualityLeading() {
    List<IndexCandidate> candidates =
        generator.generate(
            List.of(
                finding("R1", "orders", "customer_id"), // equality filter
                finding("R4", "orders", "created_at"))); // sort

    // Composite covers both → exactly one candidate, not three.
    assertThat(candidates)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.table()).isEqualTo("orders");
              assertThat(c.columns())
                  .containsExactly("customer_id", "created_at"); // equality leads
              assertThat(c.accessMethod()).isEqualTo(AccessMethod.BTREE);
              assertThat(c.sourceRuleIds()).containsExactly("R1", "R4");
              assertThat(c.plannerValidatable()).isTrue();
              assertThat(c.ddl())
                  .isEqualTo(
                      "CREATE INDEX idx_orders_customer_id_created_at ON orders "
                          + "(customer_id, created_at);");
            });
  }

  @Test
  void generatesASingleColumnBtreeForALoneFilter() {
    List<IndexCandidate> candidates =
        generator.generate(List.of(finding("R1", "customers", "email")));

    assertThat(candidates)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.columns()).containsExactly("email");
              assertThat(c.sourceRuleIds()).containsExactly("R1");
              assertThat(c.ddl())
                  .isEqualTo("CREATE INDEX idx_customers_email ON customers (email);");
            });
  }

  @Test
  void generatesABtreeForAnUnindexedJoinKey() {
    List<IndexCandidate> candidates =
        generator.generate(List.of(finding("R3", "order_items", "order_id")));

    assertThat(candidates)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.table()).isEqualTo("order_items");
              assertThat(c.columns()).containsExactly("order_id");
              assertThat(c.sourceRuleIds()).containsExactly("R3");
            });
  }

  @Test
  void generatesAnOrderedIndexForASortWithNoFilter() {
    List<IndexCandidate> candidates =
        generator.generate(List.of(finding("R4", "orders", "created_at")));

    assertThat(candidates)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.columns()).containsExactly("created_at");
              assertThat(c.sourceRuleIds()).containsExactly("R4");
            });
  }

  @Test
  void stillGeneratesTheCompositeForASkewedFilterPlusSort() {
    // #8-style negative control: the candidate is generated here; HypoPG (step 8) suppresses it.
    List<IndexCandidate> candidates =
        generator.generate(
            List.of(finding("R1", "orders", "status"), finding("R4", "orders", "created_at")));

    assertThat(candidates)
        .singleElement()
        .satisfies(c -> assertThat(c.columns()).containsExactly("status", "created_at"));
  }

  @Test
  void routesGinToTheNotPlannerValidatableBucketButStillEmitsDdl() {
    IndexCandidate gin =
        IndexCandidate.of(
            "events", List.of("payload"), AccessMethod.GIN, List.of("R7"), "jsonb containment");

    assertThat(gin.plannerValidatable()).isFalse();
    assertThat(gin.notValidatableReason()).contains("GIN");
    assertThat(gin.ddl())
        .isEqualTo("CREATE INDEX idx_events_payload ON events USING gin (payload);");
  }

  @Test
  void turnsAnR7FindingIntoAGinCandidateSeparateFromBtreeCandidates() {
    List<IndexCandidate> candidates =
        generator.generate(
            List.of(
                finding("R1", "events", "customer_id"), // scalar filter → btree
                finding("R7", "events", "payload"))); // containment → GIN, never merged in

    assertThat(candidates)
        .hasSize(2)
        .anySatisfy(
            c -> {
              assertThat(c.accessMethod()).isEqualTo(AccessMethod.BTREE);
              assertThat(c.columns()).containsExactly("customer_id");
            })
        .anySatisfy(
            c -> {
              assertThat(c.accessMethod()).isEqualTo(AccessMethod.GIN);
              assertThat(c.columns()).containsExactly("payload");
              assertThat(c.sourceRuleIds()).containsExactly("R7");
              assertThat(c.plannerValidatable()).isFalse();
              assertThat(c.ddl())
                  .isEqualTo("CREATE INDEX idx_events_payload ON events USING gin (payload);");
            });
  }

  @Test
  void emptyFindingsYieldNoCandidates() {
    assertThat(generator.generate(List.of())).isEmpty();
  }

  private static Finding finding(String ruleId, String table, String column) {
    return new Finding(ruleId, ruleId + " finding", table, List.of(column), Confidence.MEDIUM, "");
  }
}
