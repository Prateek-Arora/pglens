package com.pglens.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.Finding;
import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.QueryReport;
import com.pglens.engine.model.Recommendation;
import com.pglens.engine.model.ScanReport;
import com.pglens.engine.model.TableActivity;
import com.pglens.engine.model.TableWriteLoad;
import com.pglens.engine.model.TargetInfo;
import com.pglens.engine.model.ValidationResult;
import com.pglens.engine.model.ValidationResult.Status;
import com.pglens.explain.Explainer;
import com.pglens.explain.Explanation;
import com.pglens.explain.Explanation.Source;
import com.pglens.explain.ExplanationTarget;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** {@code --plain}: PgLens renders every number and warning; the model only writes the prose. */
class ExplanationRendererTest {

  private static final Recommendation VALIDATED =
      new Recommendation(
          IndexCandidate.of(
              "orders", List.of("customer_id"), AccessMethod.BTREE, List.of("R1"), ""),
          new ValidationResult(
                  Status.PLANNER_VALIDATED,
                  3943.0,
                  53.0,
                  0.987,
                  true,
                  "Planner-validated (HypoPG estimate): total cost 3943 → 53 (−98.7%).")
              .withBuildCaution("A key value may be too wide for a B-tree."));
  private static final QueryReport QUERY =
      new QueryReport(
          7L,
          "SELECT * FROM orders WHERE customer_id = $1",
          false,
          30,
          182.2,
          6.1,
          true,
          null,
          List.of(
              new Finding(
                  "R1",
                  "Selective sequential scan",
                  "orders",
                  List.of("customer_id"),
                  Finding.Confidence.HIGH,
                  "Seq Scan on orders filters customer_id (est. 8 of ~200000 rows).")),
          List.of(VALIDATED));
  private static final ExplanationTarget TARGET = new ExplanationTarget(QUERY, VALIDATED, 0);
  private static final List<TableWriteLoad> WRITE_HEAVY =
      List.of(
          new TableWriteLoad(
              "orders",
              TableWriteLoad.Level.WRITE_DOMINANT,
              new TableActivity(10, 0, 0, 5),
              "since reset",
              "Write-dominant table: 10 rows written vs 5 read."));

  @Test
  void pglensRendersTheDdlEstimateAndCautionsAroundTheProse() {
    Explanation fromModel =
        new Explanation(
            VALIDATED.candidate().ddl(),
            Source.LLM,
            "S.",
            "W.",
            "C.",
            "qwen3.5:4b",
            null,
            List.of(),
            List.of("https://www.postgresql.org/docs/16/indexes-intro.html"));

    String out = ExplanationRenderer.toHuman(List.of(TARGET), List.of(fromModel), WRITE_HEAVY);

    assertThat(out)
        .contains("1. CREATE INDEX idx_orders_customer_id ON orders (customer_id);")
        .contains("   In short: S.", "   Why it's slow: W.", "   What the index changes: C.")
        .contains("   Estimate: Planner-validated (HypoPG estimate)")
        .contains("   ⚠ A key value may be too wide for a B-tree.")
        .contains("   ⚠ Write-dominant table")
        .contains(
            "(Written by qwen3.5:4b from PgLens's findings. Numbers, tables and the index"
                + " were checked against them; the wording wasn't.)")
        .contains("Read more: https://www.postgresql.org/docs/16/indexes-intro.html")
        .contains(ExplanationRenderer.NOT_SAFE_NOTE);
  }

  @Test
  void aTemplateFallbackSaysWhy() {
    Explanation fallback =
        Explainer.templateOnly()
            .explain(TARGET)
            .withFallback(
                "no LLM answering at http://localhost:11434/v1 (is it running?)", List.of());

    String out = ExplanationRenderer.toHuman(List.of(TARGET), List.of(fallback), List.of());

    assertThat(out)
        .contains("In short: The planner estimates that an index on orders (customer_id)")
        .contains("(PgLens's template — the model's answer wasn't used: no LLM answering at")
        .doesNotContain("Write-dominant");
    assertThat(ExplanationRenderer.provenance(Explainer.templateOnly().explain(TARGET)))
        .isEqualTo("(PgLens's template.)");
  }

  @Test
  void jsonIsContract13WithAnExplanationsArray() throws Exception {
    ScanReport report =
        new ScanReport(
            ScanReport.SCHEMA_VERSION,
            "2026-09-26T00:00:00Z",
            new TargetInfo("localhost", 5432, "pglens_demo", "16.4", List.of("hypopg")),
            List.of(QUERY),
            List.of(),
            List.of(),
            List.of());
    Explanation e = Explainer.templateOnly().explain(TARGET);

    JsonNode root =
        new ObjectMapper()
            .readTree(
                ExplanationRenderer.withExplanations(
                    ScanReportRenderer.toJson(report), List.of(e)));

    assertThat(root.get("schemaVersion").asText()).isEqualTo("1.3");
    JsonNode first = root.get("explanations").get(0);
    assertThat(first.get("ddl").asText()).isEqualTo(VALIDATED.candidate().ddl());
    assertThat(first.get("source").asText()).isEqualTo("TEMPLATE");
    assertThat(first.has("summary")).isTrue();
    assertThat(first.has("fallbackReason")).isFalse(); // NON_EMPTY: omitted when absent
    assertThat(root.get("queries")).hasSize(1);
  }

  @Test
  void optionsParseAndRejectUnknownModes() {
    LlmOptions opts = new LlmOptions();
    new CommandLine(
            new Object() {
              @picocli.CommandLine.Mixin LlmOptions o = opts;
            })
        .parseArgs("--plain", "--plain-top", "5", "--llm-model", "qwen3.5:2b");

    assertThat(opts.enabled()).isTrue();
    assertThat(opts.templateOnly()).isTrue(); // the eval's blind ranking made the template default
    assertThat(opts.plainTop).isEqualTo(5);
    assertThat(opts.settings().model()).isEqualTo("qwen3.5:2b");

    LlmOptions llm = new LlmOptions();
    llm.plain = "llm";
    assertThat(llm.templateOnly()).isFalse();

    LlmOptions bad = new LlmOptions();
    bad.plain = "gpt";
    assertThatThrownBy(bad::templateOnly).hasMessageContaining("--plain must be llm or template");
  }
}
