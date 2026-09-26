package com.pglens.explain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The guard against real bad answers from the Phase 3 spike (§3a: granite4:3b, phi4-mini,
 * qwen3.5:2b on the same cases, reduced to the three prose fields) and hand-written attacks.
 */
class OutputGuardTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static String answer(String summary, String why, String change) throws Exception {
    return JSON.writeValueAsString(
        Map.of("summary", summary, "whyItIsSlow", why, "whatTheIndexChanges", change));
  }

  private static List<String> violations(String caseId, String summary, String why, String change)
      throws Exception {
    return OutputGuard.check(answer(summary, why, change), EvalCases.byId(caseId).facts())
        .violations();
  }

  // --- the spike's real failures --------------------------------------------------------------

  @Test
  void granitesInventedSecondIndexAndTenfoldRowCountAreCaught() throws Exception {
    List<String> v =
        violations(
            "18-",
            "The query runs very slowly, taking about 153,258 milliseconds.",
            "The planner estimates that scanning `movie_info` will look at about 3 million rows (out"
                + " of 148 million total).",
            "Adding an index on `movie_info(info)` (and similarly on `movie_info_idx(info)`) would"
                + " let PostgreSQL jump directly to the matching rows. This is expected to reduce"
                + " the total execution cost by about 68%.");

    assertThat(v)
        .anyMatch(s -> s.contains("\"148 million\" is not in FACTS"))
        .anyMatch(s -> s.contains("\"3 million\" is not in FACTS"))
        .anyMatch(s -> s.startsWith("index:") && s.contains("movie_info_idx(info)"))
        .noneMatch(s -> s.contains("153,258")); // a correct ms conversion of 153.3 s
  }

  @Test
  void graniteCallingTheCostDropAnExecutionTimeCutIsCaught() throws Exception {
    List<String> v =
        violations(
            "17-",
            "Creating an index on movie_info.movie_id is recommended as it could reduce the query's"
                + " execution time by about 74% based on planner estimates.",
            "The join on movie_info.movie_id has no index, so the movie_info side is scanned.",
            "Adding an index on movie_info.movie_id would allow the database to quickly locate"
                + " matching rows.");

    assertThat(v).anyMatch(s -> s.startsWith("honesty:") && s.contains("74.2%"));
  }

  @Test
  void phisDdlInTheProseIsCaught() throws Exception {
    List<String> v =
        violations(
            "09-",
            "The query performs a sequential scan on lineitem to filter rows on l_shipdate.",
            "Postgres reads every row of lineitem and keeps only those matching l_shipdate.",
            "CREATE INDEX ON lineitem (l_shipdate);");

    assertThat(v).anyMatch(s -> s.startsWith("sql:"));
  }

  @Test
  void talkingAboutAnotherFindingsNumbersIsCaught() throws Exception {
    // qwen3.5:4b's spike answer mentioned the other tables' scans; those numbers aren't this
    // index's facts any more (ADR-0043: facts carry only the findings this index addresses)
    List<String> v =
        violations(
            "17-",
            "The query takes over 150 seconds because it scans millions of rows.",
            "Joining movie_keyword, movie_info, and movie_info_idx requires scanning ~4.5 million,"
                + " ~14.8 million, and ~1.4 million rows respectively.",
            "Adding an index on movie_info.movie_id lets Postgres look up only the rows it needs.");

    assertThat(v)
        .anyMatch(s -> s.contains("\"4.5 million\""))
        .anyMatch(s -> s.contains("\"1.4 million\""))
        .noneMatch(s -> s.contains("14.8 million"))
        .noneMatch(s -> s.contains("150 seconds")); // 153.3 s, rounded
  }

  @Test
  void aNumberNearAFactPassesEvenWhenItsMeaningIsWrong() throws Exception {
    // qwen3.5:2b's "~15 million rows total" (it summed three tables) is within 5 % of the 14.8
    // million in the facts. The guard can't see meaning; the eval's M4 grading does.
    assertThat(
            violations(
                "17-",
                "The query scans ~15 million rows total due to unindexed joins.",
                "The join on movie_info.movie_id has no index, so movie_info is scanned.",
                "An index on movie_info (movie_id) lets each join look up only matching rows."))
        .isEmpty();
  }

  // --- hand-written attacks ------------------------------------------------------------------

  @Test
  void multipliersAndPromisesAreCaught() throws Exception {
    List<String> v =
        violations(
            "02-",
            "This index makes the query 10× faster and will fix the problem.",
            "Postgres reads every row of orders to find one customer's.",
            "Lookups become twice as fast.");

    assertThat(v)
        .anyMatch(s -> s.contains("\"10×\""))
        .anyMatch(s -> s.contains("twice as fast"))
        .anyMatch(s -> s.contains("will fix"));
  }

  @Test
  void aWrongUnitIsCaught() throws Exception {
    // 58.3 is the cost drop in percent, not milliseconds
    assertThat(
            violations(
                "01-",
                "The planner estimates the cost falls by 58.3%.",
                "Scanning order_items takes 58.3 ms per run.",
                "An index on order_items (order_id) lets the join look rows up."))
        .anyMatch(s -> s.contains("\"58.3 ms\""));
  }

  @Test
  void inventedTablesAndColumnsAreCaught() throws Exception {
    List<String> v =
        violations(
            "02-",
            "The planner estimates the cost of the query drops by 98.7%.",
            "Postgres scans orders; order_archive.customer_ref would also help.",
            "An index on orders (customer_id) finds `customer_name` values faster.");

    assertThat(v)
        .anyMatch(s -> s.contains("order_archive"))
        .anyMatch(s -> s.contains("customer_name"));
  }

  @Test
  void anotherIndexOnTheSameTableIsCaught() throws Exception {
    assertThat(
            violations(
                "03-",
                "The planner estimates a 63.0% lower cost.",
                "Postgres sorts orders by created_at to return a few rows.",
                "Also add an index on orders (customer_id) for the join."))
        .anyMatch(s -> s.startsWith("index:"));
  }

  @Test
  void anIndexThePlannerCouldNotCheckGetsNoPercentage() throws Exception {
    assertThat(
            violations(
                "08-",
                "A GIN index on events (payload) would cut the cost by 40%.",
                "The containment filter on payload makes Postgres check every row of events.",
                "A GIN index can answer containment lookups directly."))
        .anyMatch(s -> s.contains("not planner-validated"));
  }

  @Test
  void theCostDropMustBeCalledACost() throws Exception {
    assertThat(
            violations(
                "06-",
                "The query gets 73.6% quicker.",
                "Postgres reads every row of events to count one event_type.",
                "An index on events (event_type) reads only matching entries."))
        .anyMatch(s -> s.startsWith("honesty:"));
    assertThat(
            violations(
                "06-",
                "The planner estimates the query's cost drops by 73.6%.",
                "Postgres reads every row of events to count one event_type.",
                "An index on events (event_type) reads only matching entries."))
        .isEmpty();
  }

  @Test
  void englishAroundIndexesIsNotMistakenForAnIndex() throws Exception {
    assertThat(
            violations(
                "01-",
                "The planner estimates a 58.3% lower cost, e.g. for the join.",
                "The join on order_items.order_id (the join key) has no index on it, so all of"
                    + " order_items is scanned.",
                "With an index on the join column, i.e. order_id, each lookup reads only the"
                    + " matching order_items rows. The index on order_items (order_id) is enough."))
        .isEmpty();
  }

  // --- shape -----------------------------------------------------------------------------------

  @Test
  void theShapeIsEnforced() {
    ExplanationFacts f = EvalCases.byId("02-").facts();
    assertThat(OutputGuard.check("Sure! Here's an explanation.", f).violations())
        .containsExactly("shape: the answer must be one JSON object");
    assertThat(OutputGuard.check("{\"summary\":\"x\",\"whyItIsSlow\":\"y\"}", f).passed())
        .isFalse();
    assertThat(
            OutputGuard.check(
                    "{\"summary\":\"a\",\"whyItIsSlow\":\"b\",\"whatTheIndexChanges\":\"c\",\"caveats\":\"d\"}",
                    f)
                .violations())
        .containsExactly("shape: unexpected field \"caveats\"");
    assertThat(
            OutputGuard.check(
                    "```json\n{\"summary\":\"a\",\"whyItIsSlow\":\"b\",\"whatTheIndexChanges\":\"c\"}\n```",
                    f)
                .passed())
        .isTrue(); // a fenced JSON wrapper is tolerated; fences inside the prose are not
    String longText = "x".repeat(OutputGuard.MAX_FIELD_CHARS + 1);
    assertThat(
            OutputGuard.check(
                    "{\"summary\":\""
                        + longText
                        + "\",\"whyItIsSlow\":\"b\",\"whatTheIndexChanges\":\"c\"}",
                    f)
                .violations())
        .anyMatch(s -> s.contains("longer than"));
  }

  // --- the template is honest by construction; the guard must agree -----------------------------

  static Stream<EvalCases.Case> cases() {
    return EvalCases.all().stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void theTemplatePassesTheGuardOnEveryEvalCase(EvalCases.Case c) throws Exception {
    Explanation t = TemplateExplainer.explain(c.facts(), c.ddl());
    List<String> v =
        OutputGuard.check(answer(t.summary(), t.whyItIsSlow(), t.whatTheIndexChanges()), c.facts())
            .violations();

    assertThat(v).allMatch(s -> s.contains("longer than")); // the template may be wordier
  }
}
