package com.pglens.engine.confirm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Reading what to confirm from a scan report (ADR-0042). */
class ConfirmPlanTest {

  // Two queries; orders(customer_id) is recommended for both, orders(status) only for q1 and is
  // covered by a more general index, items(order_id) for q2. Ranked list repeats
  // orders(customer_id).
  private static final String REPORT =
      """
      {"schemaVersion":"1.2","generatedAt":"2026-09-25T10:00:00Z",
       "target":{"host":"db.internal","port":5433,"database":"shop","serverVersion":"16.4"},
       "queries":[
         {"queryId":11,"normalizedText":"SELECT * FROM orders WHERE customer_id = $1","truncated":false,
          "recommendations":[
            {"candidate":{"table":"orders","columns":["customer_id"],"accessMethod":"BTREE"},
             "validation":{"status":"PLANNER_VALIDATED","relativeDelta":0.9}},
            {"candidate":{"table":"orders","columns":["status"],"accessMethod":"BTREE"},
             "validation":{"status":"PLANNER_VALIDATED","relativeDelta":0.4}},
            {"candidate":{"table":"orders","columns":["note"],"accessMethod":"BTREE"},
             "validation":{"status":"SUPPRESSED","relativeDelta":0.01}}]},
         {"queryId":22,"normalizedText":"SELECT * FROM orders o JOIN items i ON i.order_id = o.id WHERE o.customer_id = $1","truncated":true,
          "recommendations":[
            {"candidate":{"table":"orders","columns":["customer_id"],"accessMethod":"BTREE"},
             "validation":{"status":"PLANNER_VALIDATED","relativeDelta":0.5}},
            {"candidate":{"table":"items","columns":["order_id"],"accessMethod":"BTREE"},
             "validation":{"status":"PLANNER_VALIDATED","relativeDelta":0.7,
                           "buildCaution":"Build caution: ..."}}]}],
       "topRecommendations":[
         {"queryId":11,"actionable":true,"recommendation":{"candidate":{"table":"orders","columns":["customer_id"],"accessMethod":"BTREE"}}},
         {"queryId":11,"actionable":false,"recommendation":{"candidate":{"table":"orders","columns":["status"],"accessMethod":"BTREE"}}},
         {"queryId":22,"actionable":true,"recommendation":{"candidate":{"table":"orders","columns":["customer_id"],"accessMethod":"BTREE"}}},
         {"queryId":22,"actionable":true,"recommendation":{"candidate":{"table":"items","columns":["order_id"],"accessMethod":"BTREE"}}}]}
      """;

  @Test
  void mergesTheRankedListIntoDistinctActionableIndexesWithAllTheirQueries() throws Exception {
    ConfirmPlan plan = ConfirmPlan.from(json(REPORT), 10);

    assertThat(plan.scanTarget().host()).isEqualTo("db.internal");
    assertThat(plan.scanTarget().port()).isEqualTo(5433);
    assertThat(plan.scanTarget().database()).isEqualTo("shop");
    assertThat(plan.indexes()).hasSize(2);

    ConfirmPlan.IndexToConfirm first = plan.indexes().get(0);
    assertThat(first.rank()).isEqualTo(1);
    assertThat(first.ddl()).isEqualTo("CREATE INDEX ON orders (customer_id);");
    assertThat(first.queries())
        .extracting(ConfirmPlan.QueryEstimate::queryId)
        .containsExactly(11L, 22L);
    assertThat(first.queries())
        .extracting(ConfirmPlan.QueryEstimate::estimatedDrop)
        .containsExactly(0.9, 0.5);
    assertThat(first.queries().get(1).truncated()).isTrue();

    ConfirmPlan.IndexToConfirm second = plan.indexes().get(1);
    assertThat(second.rank()).isEqualTo(2);
    assertThat(second.candidate().table()).isEqualTo("items");
    assertThat(second.buildCaution()).isEqualTo("Build caution: ...");
  }

  @Test
  void topCountsDistinctIndexes() throws Exception {
    assertThat(ConfirmPlan.from(json(REPORT), 1).indexes()).hasSize(1);
  }

  @Test
  void anOlderReportWithoutAPortStillReads() throws Exception {
    ConfirmPlan plan =
        ConfirmPlan.from(json(REPORT.replace("\"port\":5433,", "").replace("1.2", "1.1")), 10);
    assertThat(plan.scanTarget().port()).isNull();
  }

  @Test
  void refusesSomethingThatIsntAScanReport() {
    assertThatThrownBy(() -> ConfirmPlan.from(json("{\"indexes\":[]}"), 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Not a PgLens scan report");
  }

  private static JsonNode json(String s) throws Exception {
    return new ObjectMapper().readTree(s);
  }
}
