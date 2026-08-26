package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.ConnectionTarget;
import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.Recommendation;
import com.pglens.engine.model.ValidationResult.Status;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link HypoPGValidator} — the decisive gate — against the real monitored
 * image (hypopg installed). Seeds a small <b>ANALYZE</b>d dataset so generic-plan selectivity is
 * real: {@code customer_id} is high-cardinality (an index helps → validated), {@code status} is a
 * single value (n_distinct = 1, so the planner estimates the filter returns everything → the index
 * is ignored → suppressed). Asserts on plan-derived facts (used / not-used / relative cost), never
 * wall-clock, and that no hypothetical index is left behind.
 */
@Tag("it")
@Testcontainers
class HypoPGValidatorIntegrationTest {

  @Container static final PostgreSQLContainer<?> DB = MonitoredDbContainer.create();

  private static final String Q_SELECTIVE = "SELECT * FROM orders WHERE customer_id = $1";
  private static final String Q_UNSELECTIVE = "SELECT * FROM orders WHERE status = $1";

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void seedAndAnalyze() {
    MonitoredDbContainer.initSchema(DB);
    ConnectionTarget target =
        new ConnectionTarget(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), "pglens_demo");
    jdbc = new JdbcTemplate(DataSources.forScan(target));

    jdbc.execute(
        "INSERT INTO customers (full_name, email, country, segment) "
            + "SELECT 'c' || g, 'c' || g || '@example.com', 'US', 'standard' "
            + "FROM generate_series(1, 1000) g");
    // 20k orders: customer_id spread over 1000 customers (selective), status all 'completed'
    // (skew).
    jdbc.execute(
        "INSERT INTO orders (customer_id, status, total_cents, created_at) "
            + "SELECT (random() * 999 + 1)::int, 'completed', (random() * 10000)::int, "
            + "now() - (g || ' minutes')::interval "
            + "FROM generate_series(1, 20000) g");
    jdbc.execute("ANALYZE customers");
    jdbc.execute("ANALYZE orders");
  }

  private static IndexCandidate btree(String table, String column) {
    return IndexCandidate.of(
        table, List.of(column), AccessMethod.BTREE, List.of("R1"), "test candidate");
  }

  @Test
  void validatesAnIndexThePlannerActuallyUsesWithARealCostDrop() {
    HypoPGValidator validator = new HypoPGValidator(jdbc);

    Recommendation rec =
        validator.validate(Q_SELECTIVE, List.of(btree("orders", "customer_id"))).get(0);

    assertThat(rec.validation().status()).isEqualTo(Status.PLANNER_VALIDATED);
    assertThat(rec.validation().indexUsed()).isTrue();
    assertThat(rec.validation().costAfter()).isLessThan(rec.validation().costBefore());
    assertThat(rec.validation().relativeDelta()).isGreaterThanOrEqualTo(0.15);
    assertThat(rec.validation().label()).contains("Estimate");
    assertNoHypotheticalIndexesLeft();
  }

  @Test
  void suppressesAnIndexThePlannerWillNotUse() {
    // status is a single value → the planner estimates the filter selects all rows → index ignored.
    HypoPGValidator validator = new HypoPGValidator(jdbc);

    Recommendation rec =
        validator.validate(Q_UNSELECTIVE, List.of(btree("orders", "status"))).get(0);

    assertThat(rec.validation().status()).isEqualTo(Status.SUPPRESSED);
    assertThat(rec.validation().indexUsed()).isFalse();
    assertNoHypotheticalIndexesLeft();
  }

  @Test
  void suppressesAUsefulIndexWhenItsWinIsBelowTheThreshold() {
    // Same selective candidate, but an absurdly high threshold → used, yet not "enough" →
    // suppressed.
    HypoPGValidator strict = new HypoPGValidator(jdbc, 0.999999);

    Recommendation rec =
        strict.validate(Q_SELECTIVE, List.of(btree("orders", "customer_id"))).get(0);

    assertThat(rec.validation().status()).isEqualTo(Status.SUPPRESSED);
    assertThat(rec.validation().indexUsed()).isTrue(); // used, just not by enough
    assertThat(rec.validation().label()).contains("threshold");
    assertNoHypotheticalIndexesLeft();
  }

  @Test
  void routesUnsupportedAccessMethodsToNotPlannerValidated() {
    HypoPGValidator validator = new HypoPGValidator(jdbc);
    IndexCandidate gin =
        IndexCandidate.of(
            "events", List.of("payload"), AccessMethod.GIN, List.of("R7"), "jsonb containment");

    Recommendation rec =
        validator.validate("SELECT * FROM events WHERE payload @> $1", List.of(gin)).get(0);

    assertThat(rec.validation().status()).isEqualTo(Status.NOT_PLANNER_VALIDATED);
    assertThat(rec.validation().label()).contains("GIN");
    assertNoHypotheticalIndexesLeft();
  }

  private void assertNoHypotheticalIndexesLeft() {
    Integer live = jdbc.queryForObject("SELECT count(*) FROM hypopg()", Integer.class);
    assertThat(live).as("no hypothetical index left behind").isZero();
  }
}
