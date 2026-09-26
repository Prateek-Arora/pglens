package com.pglens.explain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.explain.ExplanationFacts.FindingFact;
import org.junit.jupiter.api.Test;

/** Facts are built from real scan reports (the frozen eval cases). */
class FactsBuilderTest {

  @Test
  void carriesTheMeasuredStatsTheIndexAndThePlannerEstimate() {
    ExplanationFacts f = EvalCases.byId("01-").facts();

    assertThat(f.measured().calls()).isEqualTo("30");
    assertThat(f.measured().meanTime()).isEqualTo("13.2 ms");
    assertThat(f.index().table()).isEqualTo("order_items");
    assertThat(f.index().columns()).containsExactly("order_id");
    assertThat(f.index().method()).isEqualTo("B-tree");
    assertThat(f.plannerEstimate().costBefore()).isEqualTo("9,761");
    assertThat(f.plannerEstimate().costAfter()).isEqualTo("4,071");
    assertThat(f.plannerEstimate().costDrop()).isEqualTo("58.3%");
    assertThat(f.status()).startsWith("planner-validated");
  }

  @Test
  void keepsOnlyTheFindingsThisIndexAddresses() {
    // lineitem(l_quantity): the same scan also filters l_shipmode and l_shipinstruct — not this
    // index
    ExplanationFacts f = EvalCases.byId("12-").facts();

    assertThat(f.findings())
        .extracting(FindingFact::columns)
        .containsExactly(java.util.List.of("l_quantity"));
    assertThat(f.otherFindingsInQuery()).isEqualTo(2);
  }

  @Test
  void aTwoColumnIndexAddressesBothItsFilterAndItsSort() {
    ExplanationFacts f = EvalCases.byId("04-").facts();

    assertThat(f.findings()).extracting(FindingFact::rule).containsExactly("R1", "R4");
    assertThat(f.otherFindingsInQuery()).isZero();
  }

  @Test
  void aFindingReportedTwiceIsCountedOnce() {
    // TPC-H Q22 reports the customer.c_acctbal scan twice
    ExplanationFacts f = EvalCases.byId("14-").facts();

    assertThat(f.findings()).hasSize(1);
    assertThat(f.otherFindingsInQuery()).isEqualTo(1);
  }

  @Test
  void aGinIndexHasNoEstimateAndSaysWhy() {
    ExplanationFacts f = EvalCases.byId("08-").facts();

    assertThat(f.plannerValidated()).isFalse();
    assertThat(f.plannerEstimate()).isNull();
    assertThat(f.index().method()).isEqualTo("GIN");
    assertThat(f.status()).startsWith("not planner-validated").contains("GIN");
  }

  @Test
  void longQueriesAreCutToThePromptBudget() {
    ExplanationFacts f = EvalCases.byId("17-").facts(); // a JOB query of ~1,750 chars

    assertThat(f.query()).hasSizeLessThanOrEqualTo(FactsBuilder.QUERY_MAX_CHARS + 2);
    assertThat(f.queryTruncated()).isTrue();
    assertThat(f.alsoValidatedForOtherQueries()).isEqualTo(44);
  }

  @Test
  void theSameTargetAlwaysBuildsEqualFacts() {
    EvalCases.Case c = EvalCases.byId("19-");
    assertThat(FactsBuilder.build(c.target())).isEqualTo(FactsBuilder.build(c.target()));
  }
}
