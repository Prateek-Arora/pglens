package com.pglens.explain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.explain.Explanation.Source;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The template runs on all 24 frozen eval cases — it is the fallback for every one of them. */
class TemplateExplainerTest {

  static Stream<EvalCases.Case> cases() {
    return EvalCases.all().stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void namesTheGoldTableAndColumnsWithoutSql(EvalCases.Case c) {
    Explanation e = TemplateExplainer.explain(c.facts(), c.ddl());
    String all = e.summary() + ' ' + e.whyItIsSlow() + ' ' + e.whatTheIndexChanges();

    assertThat(e.source()).isEqualTo(Source.TEMPLATE);
    assertThat(e.ddl()).isEqualTo(c.ddl());
    c.gold().get("mustName").forEach(name -> assertThat(all).contains(name.asText()));
    assertThat(all).doesNotContainPattern("(?i)\\b(CREATE|DROP|ALTER)\\s+INDEX\\b");
    assertThat(e.docs()).isNotEmpty();
  }

  @Test
  void aValidatedIndexLeadsWithThePlannerEstimate() {
    Explanation e = TemplateExplainer.explain(EvalCases.byId("02-").facts(), "ddl");

    assertThat(e.summary())
        .isEqualTo(
            "The planner estimates that an index on orders (customer_id) cuts this query's cost"
                + " by 98.7% (3,943 → 53). The query ran 30 times, taking 6.1 ms on average"
                + " (182.2 ms in total). The same index also passed the planner check for 1 other"
                + " query.");
    assertThat(e.whatTheIndexChanges())
        .isEqualTo(
            "With an index on orders (customer_id), Postgres can go straight to the matching rows"
                + " instead of reading the whole table.");
  }

  @Test
  void aJoinIndexExplainsTheScanItRemoves() {
    Explanation e = TemplateExplainer.explain(EvalCases.byId("01-").facts(), "ddl");

    assertThat(e.whyItIsSlow())
        .startsWith(
            "The join looks up rows of order_items by order_id, which has no index, so Postgres"
                + " scans order_items instead.")
        .contains("PgLens saw: Join on order_items.order_id has no index")
        .contains("scanned (~500,000 rows)");
    assertThat(e.whyItIsSlow()).doesNotContain("customer_id"); // another finding's column
  }

  @Test
  void aFilterPlusSortIndexExplainsBoth() {
    Explanation e = TemplateExplainer.explain(EvalCases.byId("05-").facts(), "ddl");

    assertThat(e.whatTheIndexChanges())
        .isEqualTo(
            "With an index on orders (status, created_at), Postgres can go straight to the rows"
                + " matching status, already in created_at order, and stop after the first few.");
  }

  @Test
  void aGinIndexClaimsNoEstimate() {
    Explanation e = TemplateExplainer.explain(EvalCases.byId("08-").facts(), "ddl");

    assertThat(e.summary())
        .startsWith(
            "A GIN index on events (payload) could serve this query's filter, but PgLens could not"
                + " check it with the planner.")
        .doesNotContain("%");
    assertThat(e.whatTheIndexChanges()).contains("containment");
    assertThat(e.docs()).anyMatch(u -> u.contains("gin-intro"));
  }

  @Test
  void rawEngineNumbersAreGroupedForReading() {
    assertThat(
            TemplateExplainer.readableNumbers(
                "Seq Scan on cast_info filters note (est. 6379 of ~36236964 rows, total cost"
                    + " 479201.03)."))
        .isEqualTo(
            "Seq Scan on cast_info filters note (est. 6,379 of ~36,236,964 rows, total cost"
                + " 479,201).");
    assertThat(TemplateExplainer.readableNumbers("sort node cost 4686.59; t1.col2 = $1234"))
        .isEqualTo("sort node cost 4,687; t1.col2 = $1234");
  }

  @Test
  void aSingleCallIsSingular() {
    assertThat(TemplateExplainer.explain(EvalCases.byId("17-").facts(), "ddl").summary())
        .contains("ran once, taking 153.3 s (about 2.6 min).")
        .contains("for 44 other queries");
  }

  @Test
  void everyCaseHasGold() {
    assertThat(EvalCases.all()).hasSize(24).allMatch(c -> c.gold() != null);
    assertThat(EvalCases.all().stream().filter(c -> c.split().equals("dev")).count()).isEqualTo(12);
    List<String> heldOut =
        EvalCases.all().stream()
            .filter(c -> c.split().equals("heldout"))
            .map(c -> c.id().substring(0, 2))
            .toList();
    assertThat(heldOut)
        .containsExactly("01", "03", "05", "07", "11", "13", "15", "16", "19", "21", "23", "24");
  }
}
