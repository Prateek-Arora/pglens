package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.ConnectionTarget;
import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.IndexFootprint;
import com.pglens.engine.model.ValidationResult;
import com.pglens.engine.model.ValidationResult.Status;
import com.pglens.engine.model.ValueRangeEstimate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Phase 2.5 evidence on a validated index, against the real monitored image (ADR-0038): a
 * skewed column's value range (the heavy common value gains far less than the generic plan claims),
 * HypoPG's footprint estimate, no range for shapes we must not guess, safe quoting of a hostile
 * value — all under the read-only session, leaving no hypothetical index behind.
 */
@Tag("it")
@Testcontainers
class ValueRangeIntegrationTest {

  @Container static final PostgreSQLContainer DB = MonitoredDbContainer.create();

  // Evil but valid data: if it were concatenated instead of server-quoted, the variant SQL would
  // break (and could, without the read-only guard, do damage).
  private static final String HOSTILE = "x'); DROP TABLE orders; --";

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void seedSkewedData() {
    MonitoredDbContainer.initSchema(DB);
    JdbcTemplate owner =
        new JdbcTemplate(
            DataSources.forScan(
                new ConnectionTarget(
                    DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), "pglens_demo")));
    owner.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE");
    owner.execute(
        "INSERT INTO customers (full_name, email, country, segment) "
            + "SELECT 'c' || g, 'c' || g || '@example.com', "
            + "CASE WHEN g <= 1200 THEN '"
            + HOSTILE.replace("'", "''")
            + "' ELSE 'c' || g END, 'standard' "
            + "FROM generate_series(1, 3000) g");
    // 40k orders: customer 1 owns 30 % (the hot key), the rest spread over 2999 customers.
    owner.execute(
        "INSERT INTO orders (customer_id, status, total_cents, created_at) "
            + "SELECT CASE WHEN g % 10 < 3 THEN 1 ELSE 2 + (g % 2999) END, 'completed', "
            + "(g % 10000), now() - (g || ' minutes')::interval FROM generate_series(1, 40000) g");
    owner.execute("ANALYZE customers");
    owner.execute("ANALYZE orders");

    // The validator itself runs the way PgLens always does: read-only session guards.
    jdbc =
        new JdbcTemplate(
            DataSources.forScan(
                new ConnectionTarget(
                    DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), "pglens_demo")));
    DataSources.applySessionGuards(jdbc);
  }

  private static ValidationResult validate(String sql, String table, String column) {
    IndexCandidate c =
        IndexCandidate.of(table, List.of(column), AccessMethod.BTREE, List.of("R1"), "test");
    return new HypoPGValidator(jdbc).validateDdl(sql, c.ddl(), AccessMethod.BTREE);
  }

  @Test
  void aHotKeyGetsAWorstCaseFarBelowTheGenericEstimate() {
    ValidationResult v =
        validate("SELECT * FROM orders WHERE customer_id = $1", "orders", "customer_id");

    assertThat(v.status()).isEqualTo(Status.PLANNER_VALIDATED);
    ValueRangeEstimate range = v.valueRange();
    assertThat(range).isNotNull();
    assertThat(range.column()).isEqualTo("orders.customer_id");
    assertThat(range.valuesSampled()).isBetween(2, 4);
    // The hot customer (30 % of rows) is the worst case, and it's well below the generic claim.
    assertThat(range.worstValueFrequency()).isGreaterThan(0.25);
    assertThat(range.worstRelativeDrop()).isLessThan(v.relativeDelta() - 0.2);
    assertThat(range.bestRelativeDrop()).isGreaterThan(range.worstRelativeDrop());
    // The value itself is never reported — only its frequency.
    assertThat(range.label()).contains("of rows").doesNotContain("'1'");

    IndexFootprint size = v.footprint();
    assertThat(size).isNotNull();
    assertThat(size.estimatedIndexBytes()).isPositive();
    assertThat(size.tableBytes()).isPositive();
    assertThat(size.label()).contains("HypoPG estimate");
    assertNoHypotheticalIndexesLeft();
  }

  @Test
  void aRangePredicateGetsNoValueRangeButStillAFootprint() {
    ValidationResult v =
        validate("SELECT * FROM orders WHERE created_at >= $1", "orders", "created_at");

    if (v.status() == Status.PLANNER_VALIDATED) {
      assertThat(v.valueRange()).isNull();
      assertThat(v.footprint()).isNotNull();
    } else {
      // Generic range selectivity may not pass the gate — then there's no evidence at all.
      assertThat(v.valueRange()).isNull();
      assertThat(v.footprint()).isNull();
    }
    assertNoHypotheticalIndexesLeft();
  }

  @Test
  void aHostileCommonValueIsQuotedByTheServerAndPlansSafely() {
    ValidationResult v =
        validate("SELECT * FROM customers WHERE country = $1", "customers", "country");

    assertThat(v.status()).isEqualTo(Status.PLANNER_VALIDATED);
    // The hostile value is the heaviest MCV (40 % of rows); it planned fine, so it was quoted.
    assertThat(v.valueRange()).isNotNull();
    assertThat(v.valueRange().worstValueFrequency()).isGreaterThan(0.35);
    assertThat(v.valueRange().label()).doesNotContain("DROP");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(40_000L);
    assertNoHypotheticalIndexesLeft();
  }

  private static void assertNoHypotheticalIndexesLeft() {
    assertThat(jdbc.queryForObject("SELECT count(*) FROM hypopg()", Long.class)).isZero();
  }
}
