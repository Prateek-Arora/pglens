package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pglens.engine.PgLensEngine;
import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.ConnectionTarget;
import com.pglens.engine.model.QueryReport;
import com.pglens.engine.model.RankBy;
import com.pglens.engine.model.Recommendation;
import com.pglens.engine.model.ScanReport;
import com.pglens.engine.model.ValidationResult.Status;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration test for the whole engine facade against the real monitored image. Seeds a
 * small ANALYZEd dataset, resets {@code pg_stat_statements} so the leaderboard is deterministic,
 * warms three representative statements, then drives {@link PgLensEngine#scan} and {@link
 * PgLensEngine#explain} once and asserts the honesty guarantees the pipeline must uphold:
 *
 * <ul>
 *   <li>a selective filter is <b>planner-validated</b> and reaches the cross-query top list;
 *   <li>the jsonb {@code @>} query surfaces a <b>GIN</b> rec that is <b>not planner-validated</b>
 *       with <b>no fabricated delta</b>, and is kept out of the validated top list;
 *   <li>a legitimate full-scan aggregate yields <b>no</b> validated recommendation;
 *   <li>no hypothetical index is left on the scan connection after a full scan;
 *   <li>the scan session is <b>read-only</b> at the database — writes are rejected.
 * </ul>
 *
 * <p>Assertions are on plan-derived facts (validated / not-validated / used), never wall-clock, so
 * they are CI-stable.
 */
@Tag("it")
@Testcontainers
class PgLensEngineIntegrationTest {

  @Container static final PostgreSQLContainer<?> DB = MonitoredDbContainer.create();

  private static final String Q_SELECTIVE =
      "SELECT * FROM orders WHERE customer_id = 7 ORDER BY created_at DESC";
  private static final String Q_GIN =
      "SELECT count(*) FROM events WHERE payload @> '{\"ua\":\"agent-7\"}'";
  private static final String Q_FULLSCAN =
      "SELECT status, count(*) AS n FROM orders GROUP BY status";
  // A typed-literal range: pg_stat_statements normalizes `interval '30 days'` to `interval $1`,
  // which is a syntax error to EXPLAIN unless the capturer rewrites it to a cast (ADR-0021).
  private static final String Q_RANGE =
      "SELECT count(*) FROM orders WHERE created_at >= now() - interval '30 days'";

  private static ConnectionTarget target;
  private static PgLensEngine engine;
  private static ScanReport report;

  @BeforeAll
  static void seedWarmScan() {
    MonitoredDbContainer.initSchema(DB);
    target =
        new ConnectionTarget(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), "pglens_demo");

    JdbcTemplate seed = new JdbcTemplate(DataSources.forScan(target)); // writable — no guards
    seed.execute(
        "INSERT INTO customers (full_name, email, country, segment) "
            + "SELECT 'c' || g, 'c' || g || '@example.com', 'US', 'standard' "
            + "FROM generate_series(1, 500) g");
    // 20k orders: customer_id spread over 500 customers (selective), status all 'completed'.
    seed.execute(
        "INSERT INTO orders (customer_id, status, total_cents, created_at) "
            + "SELECT (random() * 499 + 1)::int, 'completed', (random() * 10000)::int, "
            + "now() - (g || ' minutes')::interval "
            + "FROM generate_series(1, 20000) g");
    // 20k events with a jsonb payload → the GIN fixture.
    seed.execute(
        "INSERT INTO events (customer_id, event_type, occurred_at, payload) "
            + "SELECT (random() * 499 + 1)::int, 'page_view', now() - (g || ' minutes')::interval, "
            + "jsonb_build_object('ua', 'agent-' || (g % 50)) "
            + "FROM generate_series(1, 20000) g");
    seed.execute("ANALYZE customers");
    seed.execute("ANALYZE orders");
    seed.execute("ANALYZE events");

    // Deterministic leaderboard: forget the seed's own INSERTs, then warm only our three queries.
    seed.execute("SELECT pg_stat_statements_reset()");
    for (int i = 0; i < 5; i++) {
      seed.execute(Q_SELECTIVE);
      seed.execute(Q_GIN);
      seed.execute(Q_FULLSCAN);
      seed.execute(Q_RANGE);
    }

    engine = PgLensEngine.connect(target);
    report = engine.scan(RankBy.TOTAL_TIME, 20, 1);
  }

  @AfterAll
  static void closeEngine() {
    if (engine != null) {
      engine.close();
    }
  }

  @Test
  void validatesTheSelectiveFilterEndToEndAndRanksItInTheTopList() {
    QueryReport selective = find("customer_id = $1");

    Recommendation rec =
        selective.recommendations().stream()
            .filter(
                r ->
                    r.candidate().accessMethod() == AccessMethod.BTREE
                        && r.candidate().columns().equals(List.of("customer_id")))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("expected an orders(customer_id) recommendation"));

    assertThat(rec.validation().status()).isEqualTo(Status.PLANNER_VALIDATED);
    assertThat(rec.validation().indexUsed()).isTrue();
    assertThat(rec.validation().relativeDelta()).isGreaterThanOrEqualTo(0.15);

    // It also surfaces in the cross-query top recommendations as an actionable (non-subsumed) rec.
    assertThat(report.topRecommendations())
        .anySatisfy(
            rr -> {
              assertThat(rr.actionable()).isTrue();
              assertThat(rr.candidate().table()).isEqualTo("orders");
              assertThat(rr.candidate().columns()).containsExactly("customer_id");
            });
  }

  @Test
  void surfacesTheGinRecommendationButNeverPlannerValidatesOrFabricatesItsCost() {
    QueryReport gin = find("payload @> $1");

    assertThat(gin.findings()).anySatisfy(f -> assertThat(f.ruleId()).isEqualTo("R7"));

    Recommendation rec =
        gin.recommendations().stream()
            .filter(r -> r.candidate().accessMethod() == AccessMethod.GIN)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected a GIN recommendation for payload"));

    assertThat(rec.validation().status()).isEqualTo(Status.NOT_PLANNER_VALIDATED);
    assertThat(rec.validation().costBefore()).isNull(); // no fabricated delta
    assertThat(rec.validation().costAfter()).isNull();
    assertThat(rec.validation().relativeDelta()).isNull();
    assertThat(rec.candidate().ddl()).contains("USING gin");

    // The validated top list must not include the un-validated GIN rec.
    assertThat(report.topRecommendations())
        .noneSatisfy(rr -> assertThat(rr.candidate().accessMethod()).isEqualTo(AccessMethod.GIN));
  }

  @Test
  void capturesAndDetectsARangeFilterWhoseLiteralNormalizesToATypedParameter() {
    // Regression for the silent-skip bug: `interval '30 days'` → `interval $1` must still capture,
    // and the range filter must be detected and put through HypoPG (not the GIN/not-validated
    // path).
    QueryReport range = find("created_at >= now()");

    assertThat(range.planCaptured()).as("typed-literal query is no longer skipped").isTrue();
    assertThat(range.findings())
        .anySatisfy(
            f -> {
              assertThat(f.ruleId()).isEqualTo("R1");
              assertThat(f.columns()).containsExactly("created_at");
            });

    Recommendation rec =
        range.recommendations().stream()
            .filter(
                r ->
                    r.candidate().accessMethod() == AccessMethod.BTREE
                        && r.candidate().columns().equals(List.of("created_at")))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("expected an orders(created_at) btree candidate"));
    // It went through real HypoPG validation (validated or honestly suppressed) — a measured cost,
    // never a fabricated one. Which verdict depends on generic selectivity, so we don't pin it.
    assertThat(rec.validation().costBefore()).isNotNull();
  }

  @Test
  void yieldsNoValidatedRecommendationForALegitimateFullScan() {
    QueryReport fullScan = find("GROUP BY status");

    assertThat(fullScan.recommendations())
        .as("a whole-table aggregate is a legitimate full scan — no index rec")
        .noneSatisfy(r -> assertThat(r.validation().status()).isEqualTo(Status.PLANNER_VALIDATED));
  }

  @Test
  void leavesNoHypotheticalIndexBehindAfterAFullScan() {
    assertThat(engine.hypotheticalIndexCount()).isZero();
  }

  @Test
  void explainReturnsTheStatementByIdAndEmptyForAnUnknownId() {
    long selectiveId = find("customer_id = $1").queryId();

    Optional<QueryReport> detail = engine.explain(selectiveId);
    assertThat(detail).isPresent();
    assertThat(detail.get().recommendations())
        .anySatisfy(r -> assertThat(r.validation().status()).isEqualTo(Status.PLANNER_VALIDATED));

    assertThat(engine.explain(1L)).isEmpty(); // no statement with this queryid
  }

  @Test
  void scanSessionGuardsEnforceReadOnlyAtTheDatabase() {
    JdbcTemplate jdbc = new JdbcTemplate(DataSources.forScan(target));
    DataSources.applySessionGuards(jdbc);

    assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
    assertThatThrownBy(() -> jdbc.execute("UPDATE orders SET status = 'x' WHERE id = 1"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("read-only");
    assertThatThrownBy(
            () ->
                jdbc.execute(
                    "INSERT INTO events (customer_id, event_type, occurred_at, payload) "
                        + "VALUES (1, 'probe', now(), '{}')"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("read-only");
  }

  private static QueryReport find(String needle) {
    return report.queries().stream()
        .filter(q -> q.normalizedText() != null && q.normalizedText().contains(needle))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no query report contains: " + needle));
  }
}
