package com.pglens.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pglens.engine.hygiene.WriteLoad;
import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.Finding;
import com.pglens.engine.model.Finding.Confidence;
import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.PlanNode;
import com.pglens.engine.model.PlanSummary;
import com.pglens.engine.model.QueryReport;
import com.pglens.engine.model.RankBy;
import com.pglens.engine.model.RankedRecommendation;
import com.pglens.engine.model.Recommendation;
import com.pglens.engine.model.ScanReport;
import com.pglens.engine.model.TableActivity;
import com.pglens.engine.model.TargetInfo;
import com.pglens.engine.model.ValidationResult;
import com.pglens.engine.model.ValidationResult.Status;
import com.pglens.engine.model.ValueRangeEstimate;
import com.pglens.engine.rank.Recommender;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Renderer tests over a hand-built {@link ScanReport}. The fixture also runs the real {@link
 * Recommender}, so this exercises the whole pure assembly (findings → recommendations → ranked
 * top-recs → report) that the CLI prints, without a database.
 */
class ScanReportRendererTest {

  @Test
  void humanReportShowsFindingsValidatedDdlHonestyLabelsAndMergedDuplicates() {
    String out = ScanReportRenderer.toHuman(sampleReport(), RankBy.TOTAL_TIME);

    // Header + per-query.
    assertThat(out).contains("pglens_demo @ localhost", "PostgreSQL 16.4");
    assertThat(out).contains("queryid 111", "[R1]", "[R4]");

    // The validated composite, its copy-pasteable DDL, and the honesty label.
    assertThat(out)
        .contains(
            "[validated] CREATE INDEX idx_orders_customer_id_created_at ON orders (customer_id, created_at);");
    assertThat(out).contains("Estimate from the planner, not a runtime measurement.");

    // Suppressed and not-planner-validated are shown, not hidden.
    assertThat(out).contains("[suppressed]");
    assertThat(out).contains("[not planner-validated]", "USING gin (payload)");

    // Cross-query section + near-duplicate merge note.
    assertThat(out).contains("Top index recommendations (planner-validated");
    assertThat(out)
        .contains(
            "(covered)",
            "served by idx_orders_customer_id_created_at (HypoPG-validated against query #222");

    // Phase 2.5: the write-load section with its real counts (ADR-0038).
    assertThat(out)
        .contains("Table write load", "orders: Write-dominant table: 95,000 rows written");

    // Honesty notes always present.
    assertThat(out).contains("Notes", "generic-plan planner estimates");
  }

  @Test
  void jsonIsTheVersionedContractAndOmitsNulls() throws Exception {
    String json = ScanReportRenderer.toJson(sampleReport());
    JsonNode root = new ObjectMapper().readTree(json);

    assertThat(root.get("schemaVersion").asText()).isEqualTo("1.2");
    assertThat(root.get("tableWriteLoad").get(0).get("level").asText()).isEqualTo("WRITE_DOMINANT");
    // Derived flags are part of the 1.1 contract, so a JSON consumer needn't recompute them.
    assertThat(root.get("topRecommendations").get(0).has("actionable")).isTrue();
    assertThat(root.get("topRecommendations").get(0).has("scoreBasis")).isTrue();
    assertThat(root.get("target").get("database").asText()).isEqualTo("pglens_demo");
    assertThat(root.get("queries")).hasSize(3);
    assertThat(root.get("topRecommendations")).isNotEmpty();
    assertThat(root.get("notes")).isNotEmpty();

    // NON_NULL: a not-planner-validated result has null costs — they must be absent, not null.
    assertThat(json).doesNotContain("\"costBefore\" : null");
    // A validated recommendation's status is carried as the enum name.
    assertThat(json).contains("PLANNER_VALIDATED", "NOT_PLANNER_VALIDATED", "SUPPRESSED");
  }

  @Test
  void topRecommendationsRankByTheGenericPlanAndShowTheValueRangeBesideIt() {
    // ADR-0041: the score is the generic drop; the sampled-value range is evidence next to it.
    IndexCandidate idx =
        IndexCandidate.of("orders", List.of("customer_id"), AccessMethod.BTREE, List.of("R1"), "");
    ValidationResult v =
        new ValidationResult(Status.PLANNER_VALIDATED, 1000.0, 13.0, 0.987, true, "validated")
            .withEvidence(
                new ValueRangeEstimate("orders.customer_id", 4, -0.04, 0.018, 0.99, "range"), null);
    List<RankedRecommendation> top =
        new Recommender()
            .rank(List.of(new Recommender.Weighted(9L, 1000.0, new Recommendation(idx, v))));
    ScanReport report =
        new ScanReport(
            ScanReport.SCHEMA_VERSION,
            "2026-09-25T00:00:00Z",
            new TargetInfo("localhost", 5432, "pglens_demo", "16.4", List.of("hypopg")),
            List.of(),
            top,
            List.of(),
            List.of());

    String out = ScanReportRenderer.toHuman(report, RankBy.TOTAL_TIME);

    assertThat(out)
        .contains("est. saves ~987 ms")
        .contains(
            "(−98.7% generic plan; +4.0% to −99.0% across sampled values, HypoPG planner"
                + " estimates)")
        // The worst case is below the gate, so the top list says so right under the score.
        .contains("⚠ for some common values of orders.customer_id the planner expects little");
  }

  @Test
  void humanReportHandlesAnEmptyScanGracefully() {
    ScanReport empty =
        new ScanReport(
            ScanReport.SCHEMA_VERSION,
            "2026-08-25T00:00:00Z",
            new TargetInfo("localhost", 5432, "pglens_demo", "16.4", List.of("hypopg")),
            List.of(),
            List.of(),
            List.of(),
            List.of("Index cost deltas are HypoPG generic-plan planner estimates."));

    String out = ScanReportRenderer.toHuman(empty, RankBy.TOTAL_TIME);

    assertThat(out).contains("no statements matched");
    assertThat(out).contains("(none planner-validated");
  }

  // --- fixture ----------------------------------------------------------------

  private static ScanReport sampleReport() {
    // Q1: orders filtered on customer_id and ordered by created_at → validated composite.
    IndexCandidate composite =
        IndexCandidate.of(
            "orders",
            List.of("customer_id", "created_at"),
            AccessMethod.BTREE,
            List.of("R1", "R4"),
            "equality then order");
    QueryReport q1 =
        new QueryReport(
            111L,
            "SELECT * FROM orders WHERE customer_id = $1 ORDER BY created_at DESC LIMIT $2",
            false,
            100L,
            12_000.0,
            120.0,
            true,
            PlanSummary.genericPlan(seqScan("orders", "o", "(customer_id = $1)")),
            List.of(
                new Finding(
                    "R1",
                    "Selective filter on orders.customer_id",
                    "orders",
                    List.of("customer_id"),
                    Confidence.HIGH,
                    "Seq Scan returns an estimated 1% of 1,000,000 rows."),
                new Finding(
                    "R4",
                    "Sort on orders.created_at feeds a Limit",
                    "orders",
                    List.of("created_at"),
                    Confidence.MEDIUM,
                    "Sort could be served by an ordered index.")),
            List.of(validated(composite, 1000, 400, 0.60)));

    // Q2: orders filtered only on customer_id → validated single (subsumed by Q1's composite).
    IndexCandidate single =
        IndexCandidate.of(
            "orders",
            List.of("customer_id"),
            AccessMethod.BTREE,
            List.of("R1"),
            "selective filter");
    QueryReport q2 =
        new QueryReport(
            222L,
            "SELECT * FROM orders WHERE customer_id = $1",
            false,
            50L,
            3_000.0,
            60.0,
            true,
            PlanSummary.genericPlan(seqScan("orders", "o", "(customer_id = $1)")),
            List.of(
                new Finding(
                    "R1",
                    "Selective filter on orders.customer_id",
                    "orders",
                    List.of("customer_id"),
                    Confidence.HIGH,
                    "Seq Scan on a selective predicate.")),
            List.of(validated(single, 500, 250, 0.50)));

    // Q3: a suppressed candidate and a GIN candidate that HypoPG can't validate.
    IndexCandidate skewed =
        IndexCandidate.of(
            "orders", List.of("status"), AccessMethod.BTREE, List.of("R1"), "low-cardinality");
    IndexCandidate gin =
        IndexCandidate.of(
            "events", List.of("payload"), AccessMethod.GIN, List.of("R7"), "jsonb containment");
    QueryReport q3 =
        new QueryReport(
            333L,
            "SELECT * FROM events WHERE payload @> $1",
            false,
            30L,
            1_500.0,
            50.0,
            true,
            PlanSummary.genericPlan(seqScan("events", "e", "(payload @> $1)")),
            List.of(
                new Finding(
                    "R1",
                    "Filter on events.payload",
                    "events",
                    List.of("payload"),
                    Confidence.LOW,
                    "jsonb containment predicate.")),
            List.of(suppressed(skewed), notValidated(gin)));

    List<QueryReport> queries = List.of(q1, q2, q3);

    // Rank exactly as the engine does: weight each rec by its query's real total time.
    List<Recommender.Weighted> weighted = new ArrayList<>();
    weight(weighted, queries);
    // …then, as the engine does, check each subsumed rec's query against the general index.
    List<RankedRecommendation> top =
        new Recommender()
            .rank(weighted).stream()
                .map(
                    r ->
                        r.subsumed() ? r.withCoverage(q1.recommendations().get(0).validation()) : r)
                .toList();

    return new ScanReport(
        ScanReport.SCHEMA_VERSION,
        "2026-08-25T00:00:00Z",
        new TargetInfo(
            "localhost", 5432, "pglens_demo", "16.4", List.of("hypopg", "pg_stat_statements")),
        queries,
        top,
        List.of(
            WriteLoad.assess(
                "orders", new TableActivity(90_000, 5_000, 0, 20_000), "over the test window")),
        List.of(
            "Index cost deltas are HypoPG generic-plan planner estimates, not runtime measurements.",
            "GIN/GiST recommendations are surfaced but not planner-validated."));
  }

  private static void weight(List<Recommender.Weighted> out, List<QueryReport> queries) {
    for (QueryReport q : queries) {
      for (Recommendation r : q.recommendations()) {
        out.add(new Recommender.Weighted(q.queryId(), q.totalExecMs(), r));
      }
    }
  }

  private static PlanNode seqScan(String relation, String alias, String filter) {
    return new PlanNode(
        "Seq Scan",
        false,
        0.0,
        1234.5,
        1000L,
        8,
        relation,
        alias,
        null,
        null,
        filter,
        null,
        null,
        null,
        List.of(),
        List.of(),
        null,
        null,
        null,
        null,
        List.of());
  }

  private static Recommendation validated(IndexCandidate c, double before, double after, double d) {
    String label =
        "Planner-validated (HypoPG estimate): total cost %.0f → %.0f (−%.1f%%). "
                .formatted(before, after, d * 100)
            + "Estimate from the planner, not a runtime measurement.";
    return new Recommendation(
        c, new ValidationResult(Status.PLANNER_VALIDATED, before, after, d, true, label));
  }

  private static Recommendation suppressed(IndexCandidate c) {
    return new Recommendation(
        c,
        new ValidationResult(
            Status.SUPPRESSED, 500.0, 495.0, 0.01, false, "Suppressed: planner did not use it."));
  }

  private static Recommendation notValidated(IndexCandidate c) {
    return new Recommendation(
        c,
        new ValidationResult(
            Status.NOT_PLANNER_VALIDATED,
            null,
            null,
            null,
            false,
            "Not planner-validated — HypoPG cannot simulate a GIN index."));
  }
}
