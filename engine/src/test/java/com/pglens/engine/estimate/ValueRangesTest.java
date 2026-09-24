package com.pglens.engine.estimate;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.estimate.ValueRanges.Sample;
import com.pglens.engine.model.ValueRangeEstimate;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/** Pure tests for turning per-value planner costs into a worst/best range (ADR-0038). */
class ValueRangesTest {

  private static final Offset<Double> EPS = Offset.offset(1e-9);

  @Test
  void worstIsTheLowestDropAndCarriesItsFrequency() {
    // The demo's shape: the hot customer gains ~57 %, colder ones far more.
    ValueRangeEstimate r =
        ValueRanges.of(
                List.of(
                    new Sample("orders.customer_id", 0.018, 4197.0, 1816.0),
                    new Sample("orders.customer_id", 0.006, 4039.0, 1587.0),
                    new Sample("orders.customer_id", null, 3943.0, 53.0)),
                0.987,
                0.15)
            .orElseThrow();

    assertThat(r.valuesSampled()).isEqualTo(3);
    assertThat(r.worstRelativeDrop()).isCloseTo(1 - 1816.0 / 4197.0, EPS);
    assertThat(r.worstValueFrequency()).isEqualTo(0.018);
    assertThat(r.bestRelativeDrop()).isCloseTo(1 - 53.0 / 3943.0, EPS);
    assertThat(r.label())
        .contains("3 sampled values of orders.customer_id")
        .contains("a common value of orders.customer_id (1.8% of rows)")
        .contains("generic plan: −98.7%")
        .doesNotContain("Caution");
  }

  @Test
  void aTypicalValueCanBeTheWorstCaseWithNoFrequency() {
    ValueRangeEstimate r =
        ValueRanges.of(
                List.of(new Sample("t.c", 0.5, 100.0, 10.0), new Sample("t.c", null, 100.0, 60.0)),
                0.9,
                0.15)
            .orElseThrow();
    assertThat(r.worstValueFrequency()).isNull();
    assertThat(r.label()).contains("a typical value of t.c");
  }

  @Test
  void reportsWhichParameterColumnProducedTheWorstCase() {
    // A join index: its own column is fine, but the hot value of another table's filter isn't.
    ValueRangeEstimate r =
        ValueRanges.of(
                List.of(
                    new Sample("orders.customer_id", 0.018, 1000.0, 870.0),
                    new Sample("orders.customer_id", null, 1000.0, 50.0),
                    new Sample("orders.status", 0.5, 1000.0, 400.0)),
                0.58,
                0.15)
            .orElseThrow();
    assertThat(r.column()).isEqualTo("orders.customer_id");
    assertThat(r.worstRelativeDrop()).isCloseTo(0.13, EPS);
    assertThat(r.label()).contains("values of orders.customer_id, orders.status");
  }

  @Test
  void addsACautionWhenTheWorstCaseIsBelowTheGate() {
    ValueRangeEstimate r =
        ValueRanges.of(
                List.of(new Sample("t.c", 0.4, 100.0, 95.0), new Sample("t.c", null, 100.0, 5.0)),
                0.9,
                0.15)
            .orElseThrow();
    assertThat(r.worstRelativeDrop()).isCloseTo(0.05, EPS);
    assertThat(r.label()).contains("Caution");
  }

  @Test
  void skipsUnplannableSamplesAndIsEmptyWhenNoneAreUsable() {
    assertThat(
            ValueRanges.of(
                List.of(
                    new Sample("t.c", 0.3, null, 5.0),
                    new Sample("t.c", 0.2, 100.0, null),
                    new Sample("t.c", 0.1, 0.0, 0.0)),
                0.9,
                0.15))
        .isEmpty();
    ValueRangeEstimate one =
        ValueRanges.of(
                List.of(new Sample("t.c", 0.3, null, 5.0), new Sample("t.c", 0.2, 100.0, 50.0)),
                0.9,
                0.15)
            .orElseThrow();
    assertThat(one.valuesSampled()).isEqualTo(1);
  }
}
