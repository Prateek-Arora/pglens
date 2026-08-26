package com.pglens.engine.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pglens.engine.model.PlanNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanParserTest {

  private final PlanParser parser = new PlanParser();

  private String fixture(String name) {
    try (InputStream in = getClass().getResourceAsStream("/plans/" + name)) {
      assertThat(in).as("fixture %s must be on the test classpath", name).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void parsesSeqScanWithFilterAndVerboseOutput() {
    PlanNode root = parser.parse(fixture("customers_email_seqscan.json"));
    assertThat(root.nodeType()).isEqualTo("Seq Scan");
    assertThat(root.isSeqScan()).isTrue();
    assertThat(root.relationName()).isEqualTo("customers");
    assertThat(root.totalCost()).isEqualTo(473.00);
    assertThat(root.planRows()).isEqualTo(1);
    assertThat(root.filter()).isEqualTo("(customers.email = $1)");
    assertThat(root.output()).containsExactly("id", "full_name", "email");
    assertThat(root.children()).isEmpty();
    // Generic plan → no ANALYZE actuals.
    assertThat(root.actualRows()).isNull();
  }

  @Test
  void parsesParallelPlanTreeAndFlattensPreOrder() {
    PlanNode root = parser.parse(fixture("orders_customer_id_sort.json"));
    List<PlanNode> all = root.flatten();
    assertThat(all).extracting(PlanNode::nodeType).contains("Gather Merge", "Sort", "Seq Scan");

    PlanNode seqScan = all.stream().filter(PlanNode::isSeqScan).findFirst().orElseThrow();
    assertThat(seqScan.relationName()).isEqualTo("orders");
    assertThat(seqScan.parallelAware()).isTrue(); // parallel scan is "Seq Scan" + parallelAware
    assertThat(seqScan.filter()).contains("customer_id = $1");
  }

  @Test
  void parsesHashJoinOverTwoSeqScans() {
    PlanNode root = parser.parse(fixture("order_items_join.json"));
    List<PlanNode> all = root.flatten();

    PlanNode join = all.stream().filter(PlanNode::isJoin).findFirst().orElseThrow();
    assertThat(join.nodeType()).isEqualTo("Hash Join");
    assertThat(join.joinType()).isEqualTo("Inner");
    assertThat(join.hashCond()).isEqualTo("(oi.order_id = o.id)");

    assertThat(all).filteredOn(PlanNode::isSeqScan).hasSize(2);
    assertThat(all).extracting(PlanNode::relationName).contains("order_items", "orders");
  }

  @Test
  void parsesSortFeedingLimit() {
    PlanNode root = parser.parse(fixture("orders_status_limit.json"));
    List<PlanNode> all = root.flatten();
    assertThat(all).extracting(PlanNode::nodeType).contains("Limit", "Sort");
    PlanNode sort = all.stream().filter(PlanNode::isSort).findFirst().orElseThrow();
    assertThat(sort.sortKeys()).isNotEmpty();
  }

  @Test
  void parsesGroupByAggregateWithSeqScanAndNoJoin() {
    PlanNode root = parser.parse(fixture("orders_groupby_status.json"));
    List<PlanNode> all = root.flatten();
    assertThat(all).anyMatch(PlanNode::isSeqScan);
    assertThat(all).noneMatch(PlanNode::isJoin);
  }

  @Test
  void rejectsJsonWithoutPlan() {
    assertThatThrownBy(() -> parser.parse("[{\"NotAPlan\": 1}]"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMalformedJson() {
    assertThatThrownBy(() -> parser.parse("not json")).isInstanceOf(IllegalArgumentException.class);
  }
}
