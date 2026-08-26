package com.pglens.engine.detect;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.Finding;
import com.pglens.engine.model.Finding.Confidence;
import com.pglens.engine.model.IndexInfo;
import com.pglens.engine.model.PlanNode;
import com.pglens.engine.model.TableInfo;
import com.pglens.engine.parse.PlanParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rule-by-rule detection against real captured EXPLAIN fixtures, with hand-built {@link
 * CatalogSnapshot}s so each rule's index/size logic is exercised deterministically and offline.
 */
class AntiPatternDetectorTest {

  private final PlanParser parser = new PlanParser();

  // ---------- R1: selective seq scan on an unindexed filter ----------

  @Test
  void r1FlagsSelectiveSeqScanOnUnindexedColumn() {
    PlanNode plan = parse("customers_email_seqscan.json");
    CatalogSnapshot catalog = catalog(table("customers", 100_000, pk("customers", "id")));

    List<Finding> findings = only(new SelectiveSeqScanRule(), plan, catalog);

    assertThat(findings)
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.ruleId()).isEqualTo("R1");
              assertThat(f.table()).isEqualTo("customers");
              assertThat(f.columns()).containsExactly("email");
              assertThat(f.confidence()).isEqualTo(Confidence.HIGH); // 1 of 100k rows
            });
  }

  @Test
  void r1SilentWhenTheColumnIsAlreadyIndexed() {
    PlanNode plan = parse("customers_email_seqscan.json");
    CatalogSnapshot catalog =
        catalog(
            table(
                "customers",
                100_000,
                pk("customers", "id"),
                btree("customers", "customers_email_idx", "email")));

    assertThat(only(new SelectiveSeqScanRule(), plan, catalog)).isEmpty();
  }

  @Test
  void r1SkipsTinyTables() {
    PlanNode plan = parse("customers_email_seqscan.json");
    CatalogSnapshot catalog = catalog(table("customers", 100, pk("customers", "id")));

    assertThat(only(new SelectiveSeqScanRule(), plan, catalog)).isEmpty();
  }

  @Test
  void r1FlagsARangeFilterWhoseParameterIsInsideAnExpression() {
    // demo #3: `created_at >= now() - interval '7 days'` → Filter (orders.created_at >= (now() -
    // $1))
    PlanNode plan = parse("orders_created_at_range.json");
    CatalogSnapshot catalog = catalog(table("orders", 400_000, pk("orders", "id")));

    assertThat(only(new SelectiveSeqScanRule(), plan, catalog))
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.ruleId()).isEqualTo("R1");
              assertThat(f.table()).isEqualTo("orders");
              assertThat(f.columns()).containsExactly("created_at");
            });
  }

  // ---------- R3: unindexed join key ----------

  @Test
  void r3FlagsUnindexedJoinKeyButNotThePkSide() {
    PlanNode plan = parse("order_items_join.json"); // (oi.order_id = o.id)
    CatalogSnapshot catalog =
        catalog(
            table("order_items", 1_000_000, pk("order_items", "id")),
            table("orders", 40_000, pk("orders", "id")));

    List<Finding> findings = only(new UnindexedJoinRule(), plan, catalog);

    assertThat(findings)
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.ruleId()).isEqualTo("R3");
              assertThat(f.table())
                  .isEqualTo("order_items"); // orders.id is the PK side → not flagged
              assertThat(f.columns()).containsExactly("order_id");
              assertThat(f.confidence()).isEqualTo(Confidence.HIGH); // 1M-row side
            });
  }

  // ---------- R4: sort feeding a Limit ----------

  @Test
  void r4FlagsSortFeedingLimit() {
    PlanNode plan = parse("orders_status_limit.json"); // Limit → Sort(orders.created_at DESC)
    CatalogSnapshot catalog = catalog(table("orders", 40_000, pk("orders", "id")));

    List<Finding> findings = only(new SortLimitRule(), plan, catalog);

    assertThat(findings)
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.ruleId()).isEqualTo("R4");
              assertThat(f.table()).isEqualTo("orders");
              assertThat(f.columns()).containsExactly("created_at");
              // Evidence must be fully interpolated — no raw format placeholders leak to the user.
              assertThat(f.evidence())
                  .contains("orders", "created_at")
                  .doesNotContain("%s", "%.2f");
            });
  }

  @Test
  void r4SilentWithoutALimit() {
    PlanNode plan = parse("orders_customer_id_sort.json"); // has a Sort, but no Limit above it
    CatalogSnapshot catalog = catalog(table("orders", 40_000, pk("orders", "id")));

    assertThat(only(new SortLimitRule(), plan, catalog)).isEmpty();
  }

  @Test
  void r4SilentWhenSortColumnAlreadyIndexed() {
    PlanNode plan = parse("orders_status_limit.json");
    CatalogSnapshot catalog =
        catalog(
            table(
                "orders",
                40_000,
                pk("orders", "id"),
                btree("orders", "orders_created_at_idx", "created_at")));

    assertThat(only(new SortLimitRule(), plan, catalog)).isEmpty();
  }

  // ---------- R7: containment/existence filter → GIN candidate ----------

  @Test
  void r7FlagsAnUnindexedJsonbContainmentSeqScan() {
    PlanNode plan = parse("events_payload_jsonb.json"); // Seq Scan, Filter (events.payload @> $1)
    CatalogSnapshot catalog = catalog(table("events", 300_000, pk("events", "id")));

    List<Finding> findings = only(new GinCandidateRule(), plan, catalog);

    assertThat(findings)
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.ruleId()).isEqualTo("R7");
              assertThat(f.table()).isEqualTo("events");
              assertThat(f.columns()).containsExactly("payload");
              // Honesty must be in the evidence, and no format placeholders may leak.
              assertThat(f.evidence())
                  .contains("@>", "GIN", "Not planner-validated")
                  .doesNotContain("%s", "%d");
            });
  }

  @Test
  void r7SilentWhenTheJsonbColumnAlreadyHasAGinIndex() {
    PlanNode plan = parse("events_payload_jsonb.json");
    CatalogSnapshot catalog =
        catalog(
            table(
                "events",
                300_000,
                pk("events", "id"),
                gin("events", "events_payload_gin", "payload")));

    assertThat(only(new GinCandidateRule(), plan, catalog)).isEmpty();
  }

  // ---------- default detector: aggregation ----------

  @Test
  void defaultDetectorAggregatesRulesOnOnePlan() {
    PlanNode plan = parse("orders_status_limit.json");
    CatalogSnapshot catalog = catalog(table("orders", 40_000, pk("orders", "id")));

    List<Finding> findings = new AntiPatternDetector().detect(plan, catalog);

    assertThat(findings)
        .anySatisfy(
            f -> {
              assertThat(f.ruleId()).isEqualTo("R1"); // the status filter
              assertThat(f.columns()).containsExactly("status");
            })
        .anySatisfy(
            f -> {
              assertThat(f.ruleId()).isEqualTo("R4"); // the created_at sort
              assertThat(f.columns()).containsExactly("created_at");
            });
  }

  @Test
  void nullPlanYieldsNoFindings() {
    assertThat(new AntiPatternDetector().detect(null, CatalogSnapshot.empty())).isEmpty();
  }

  // ---------- helpers ----------

  private List<Finding> only(Rule rule, PlanNode plan, CatalogSnapshot catalog) {
    return new AntiPatternDetector(List.of(rule)).detect(plan, catalog);
  }

  private PlanNode parse(String fixture) {
    try (InputStream in = getClass().getResourceAsStream("/plans/" + fixture)) {
      assertThat(in).as("fixture %s on classpath", fixture).isNotNull();
      return parser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static CatalogSnapshot catalog(TableInfo... tables) {
    Map<String, TableInfo> byName = new LinkedHashMap<>();
    for (TableInfo t : tables) {
      byName.put(t.name(), t);
    }
    return new CatalogSnapshot(byName);
  }

  private static TableInfo table(String name, long reltuples, IndexInfo... indexes) {
    return new TableInfo(name, reltuples, List.of(indexes));
  }

  private static IndexInfo pk(String table, String column) {
    return new IndexInfo(table, table + "_pkey", List.of(column), true, true, "btree", null);
  }

  private static IndexInfo btree(String table, String name, String... columns) {
    return new IndexInfo(table, name, List.of(columns), false, false, "btree", null);
  }

  private static IndexInfo gin(String table, String name, String... columns) {
    return new IndexInfo(table, name, List.of(columns), false, false, "gin", null);
  }
}
