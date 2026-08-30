package com.pglens.server.trend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pglens.server.grpc.Tokens;
import com.pglens.server.persistence.MonitoredDbRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The trend queries (Phase 2, Step 7) end to end over a real metadata Postgres. Seeds a hand-built
 * {@code query_stats} time-series across two adjacent weekly windows and asserts the three
 * questions the service answers: a query's mean-over-time series, the week-over-week top movers
 * (ranked by absolute delta, with an honest {@code null} percent change for a query that has no
 * prior load), and newly-appeared slow queries (first-ever row in the window, past the "slow"
 * floor). Proves the DoD "trend query returns a query's mean_exec_time over time + a top-movers
 * list".
 */
@Tag("it")
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "pglens.grpc.port=0",
      "pglens.analysis.initial-delay-ms=3600000",
      "pglens.analysis.interval-ms=3600000"
    })
class TrendQueryIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> METADATA =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:0.8.6-pg16")
              .asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", METADATA::getJdbcUrl);
    registry.add("spring.datasource.username", METADATA::getUsername);
    registry.add("spring.datasource.password", METADATA::getPassword);
  }

  @Autowired TrendService trends;
  @Autowired MonitoredDbRepository monitoredDbs;
  @Autowired JdbcTemplate jdbc;

  private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");
  private static final Duration WEEK = Duration.ofDays(7);

  private long dbId;

  @BeforeEach
  void setUp() {
    jdbc.execute("TRUNCATE monitored_dbs, query_texts, query_stats RESTART IDENTITY CASCADE");
    dbId = monitoredDbs.register("demo", "monitored-db", Tokens.sha256Hex("t"));

    // Two established queries, each present in both windows:
    //   q100 regressed (1000 → 3000 ms),  q200 improved (5000 → 1000 ms).
    text(100, "SELECT * FROM orders WHERE customer_id = $1");
    stat(100, NOW.minus(Duration.ofDays(10)), 1000, 100); // prior window
    stat(100, NOW.minus(Duration.ofDays(3)), 3000, 100); // recent window

    text(200, "SELECT * FROM items WHERE sku = $1");
    stat(200, NOW.minus(Duration.ofDays(9)), 5000, 50); // prior window
    stat(200, NOW.minus(Duration.ofDays(2)), 1000, 50); // recent window

    // Two queries that appear for the FIRST time in the recent window (no prior row at all):
    //   q300 is slow (2500 ms), q400 is trivial (50 ms).
    text(300, "SELECT ... FROM reports r JOIN ... GROUP BY ...");
    stat(300, NOW.minus(Duration.ofDays(1)), 2500, 50);

    text(400, "SELECT 1");
    stat(400, NOW.minus(Duration.ofDays(1)), 50, 10);
  }

  @Test
  void seriesReturnsAQueryMeanExecTimeOverTimeOldestFirst() {
    QueryTrend trend = trends.series(dbId, 100, NOW.minus(Duration.ofDays(14)), NOW);

    assertThat(trend.queryid()).isEqualTo(100);
    assertThat(trend.normalizedText()).contains("orders");
    assertThat(trend.points())
        .extracting(TrendPoint::meanExecTimeMs)
        .containsExactly(10.0, 30.0); // 1000/100 then 3000/100, oldest first
    assertThat(trend.points().get(0).capturedAt()).isEqualTo(NOW.minus(Duration.ofDays(10)));
  }

  @Test
  void topMoversRankByAbsoluteDeltaWithHonestPercentChange() {
    List<TopMover> movers = trends.topMovers(dbId, NOW, WEEK, 10);

    // Worst regression first: q300 (+2500, new load), q100 (+2000), q400 (+50), q200 (-4000).
    assertThat(movers).extracting(TopMover::queryid).containsExactly(300L, 100L, 400L, 200L);

    TopMover regressed = movers.get(1);
    assertThat(regressed.queryid()).isEqualTo(100);
    assertThat(regressed.recentTotalMs()).isCloseTo(3000.0, within(1e-6));
    assertThat(regressed.priorTotalMs()).isCloseTo(1000.0, within(1e-6));
    assertThat(regressed.deltaMs()).isCloseTo(2000.0, within(1e-6));
    assertThat(regressed.pctChange()).isCloseTo(200.0, within(1e-6));

    // A newly-active query has no prior baseline → percent change is absent, not a fabricated ∞.
    TopMover newLoad = movers.get(0);
    assertThat(newLoad.queryid()).isEqualTo(300);
    assertThat(newLoad.priorTotalMs()).isCloseTo(0.0, within(1e-6));
    assertThat(newLoad.pctChange()).isNull();
  }

  @Test
  void newSlowQueriesAreFirstSeenInWindowAndPastTheFloor() {
    // Threshold 500 ms admits q300 (2500) and rejects q400 (50); q100/q200 have prior history so
    // they are not "new" at all.
    List<NewSlowQuery> fresh = trends.newSlowQueries(dbId, NOW, WEEK, 500.0);

    assertThat(fresh).extracting(NewSlowQuery::queryid).containsExactly(300L);
    NewSlowQuery q = fresh.get(0);
    assertThat(q.recentTotalMs()).isCloseTo(2500.0, within(1e-6));
    assertThat(q.recentCalls()).isEqualTo(50);
    assertThat(q.meanMs()).isCloseTo(50.0, within(1e-6)); // 2500 / 50
    assertThat(q.firstSeen()).isEqualTo(NOW.minus(Duration.ofDays(1)));
    assertThat(q.normalizedText()).contains("reports");
  }

  // --- seeding helpers ---

  private void text(long queryid, String normalized) {
    jdbc.update(
        "INSERT INTO query_texts (db_id, queryid, text_hash, normalized_text, plan_captured) "
            + "VALUES (?, ?, ?, ?, true)",
        dbId,
        queryid,
        "h" + queryid,
        normalized);
  }

  private void stat(long queryid, Instant capturedAt, double totalMs, long calls) {
    OffsetDateTime at = OffsetDateTime.ofInstant(capturedAt, ZoneOffset.UTC);
    jdbc.update(
        """
        INSERT INTO query_stats
          (db_id, queryid, captured_at, agent_sample_at, calls_delta, total_exec_time_delta_ms,
           mean_exec_time_ms, rows_delta, shared_blks_hit_delta, shared_blks_read_delta)
        VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0)
        """,
        dbId,
        queryid,
        at,
        at,
        calls,
        totalMs,
        calls == 0 ? null : totalMs / calls);
  }
}
