package com.pglens.server.advice;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.hygiene.WriteLoad;
import com.pglens.engine.model.TableActivity;
import com.pglens.engine.model.TableWriteLoad;
import com.pglens.server.advice.AdviceAssembler.ValidatedRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure tests for per-index advice grouping, summing and coverage-based redundancy (ADR-0038). */
class AdviceAssemblerTest {

  private static final String NARROW =
      "CREATE INDEX idx_orders_customer_id ON orders (customer_id);";
  private static final String WIDE =
      "CREATE INDEX idx_orders_customer_id_created_at ON orders (customer_id, created_at);";
  private static final String OTHER = "CREATE INDEX idx_events_type ON events (type);";

  @Test
  void aWiderIndexValidatedForAllOfTheNarrowOnesQueriesMakesItRedundant() {
    List<IndexAdvice> advice =
        AdviceAssembler.assemble(
            List.of(
                row(NARROW, 1, 300),
                row(WIDE, 2, 500),
                row(WIDE, 1, 280), // the coverage check passed for query 1
                row(OTHER, 3, 100)),
            Map.of());

    assertThat(advice).extracting(IndexAdvice::ddl).containsExactly(WIDE, OTHER, NARROW);
    IndexAdvice wide = advice.get(0);
    // Summed only because both queries were validated against THIS index.
    assertThat(wide.estimatedMsSaved()).isEqualTo(780.0);
    assertThat(wide.queries())
        .extracting(IndexAdvice.QueryEvidence::queryId)
        .containsExactly(2L, 1L);
    assertThat(advice.get(2).actionable()).isFalse();
    assertThat(advice.get(2).redundantWith()).isEqualTo(WIDE);
  }

  @Test
  void aWiderIndexNotValidatedForTheQueryLeavesTheNarrowOneActionable() {
    List<IndexAdvice> advice =
        AdviceAssembler.assemble(List.of(row(NARROW, 1, 300), row(WIDE, 2, 500)), Map.of());

    assertThat(advice).allMatch(IndexAdvice::actionable);
  }

  @Test
  void attachesTheTablesWriteLoad() {
    TableWriteLoad load =
        WriteLoad.assess("orders", new TableActivity(5000, 0, 0, 10), "over the last 6.0 h");
    List<IndexAdvice> advice =
        AdviceAssembler.assemble(List.of(row(NARROW, 1, 300)), Map.of("orders", load));

    assertThat(advice.get(0).table()).isEqualTo("orders");
    assertThat(advice.get(0).writeLoad().level()).isEqualTo(TableWriteLoad.Level.WRITE_DOMINANT);
  }

  private static ValidatedRow row(String ddl, long queryId, double ms) {
    return new ValidatedRow(ddl, "BTREE", queryId, ms, "GENERIC_PLAN", 0.5, null, "size");
  }
}
