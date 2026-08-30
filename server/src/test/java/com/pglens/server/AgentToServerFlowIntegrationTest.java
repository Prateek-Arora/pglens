package com.pglens.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pglens.agent.config.PglensAgentProperties;
import com.pglens.agent.grpc.IngestClient;
import com.pglens.agent.grpc.ValidationClient;
import com.pglens.agent.sample.SampleCollector;
import com.pglens.agent.sample.ValidationRunner;
import com.pglens.engine.db.CatalogReader;
import com.pglens.engine.db.DataSources;
import com.pglens.engine.db.HypoPGValidator;
import com.pglens.engine.db.PlanCapturer;
import com.pglens.engine.db.StatsReader;
import com.pglens.engine.model.ConnectionTarget;
import com.pglens.proto.v1.SampleBatch;
import com.pglens.server.analysis.AnalysisService;
import com.pglens.server.grpc.GrpcServerLifecycle;
import com.pglens.server.grpc.Tokens;
import com.pglens.server.persistence.MonitoredDbRepository;
import com.pglens.server.trend.QueryTrend;
import com.pglens.server.trend.TopMover;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The full a-pull loop end to end (Phase 2, Step 10): the <em>real</em> agent runtime components
 * ({@link SampleCollector}, {@link ValidationRunner} + the gRPC clients) reading a real monitored
 * Postgres and streaming over a real gRPC transport to a booted {@link
 * com.pglens.server.PglensServerApplication} backed by a real metadata Postgres. This automates
 * what Step 9 verified by hand in compose and covers the DoD items: samples persist as deltas and a
 * trend query returns; a validation job round-trips into a {@code PLANNER_VALIDATED} recommendation
 * with a real cost drop; a {@code pg_stat_statements_reset()} mid-run keeps deltas correct (the
 * plan §10 self-check); an unknown token is rejected.
 *
 * <p>The scheduled analysis job is parked (huge initial delay) and driven with {@code run()} so
 * counts are deterministic; likewise the agent components are constructed here and their {@code
 * sample()} / {@code validatePending()} are called directly rather than waiting on their
 * schedulers.
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
class AgentToServerFlowIntegrationTest {

  private static final String TOKEN = "agent-secret-token";
  private static final String DB_NAME = "demo";

  @Container
  static final PostgreSQLContainer<?> METADATA =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:0.8.6-pg16")
              .asCompatibleSubstituteFor("postgres"));

  @Container
  static final PostgreSQLContainer<?> MONITORED =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pglens/monitored-db:0.0.0")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("pglens_demo")
          .withCommand(
              "postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=pg_stat_statements");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", METADATA::getJdbcUrl);
    registry.add("spring.datasource.username", METADATA::getUsername);
    registry.add("spring.datasource.password", METADATA::getPassword);
  }

  // Monitored-side fixtures (shared across tests; pg_stat_statements is reset per test).
  private static JdbcTemplate su; // superuser: seed / warm / reset — the writable path
  private static SingleConnectionDataSource agentDs; // the agent's monitored connection
  private static JdbcTemplate agentJdbc;
  private static StatsReader statsReader;
  private static CatalogReader catalogReader;
  private static PlanCapturer planCapturer;
  private static HypoPGValidator validator;

  @Autowired GrpcServerLifecycle grpcServer;
  @Autowired MonitoredDbRepository monitoredDbs;
  @Autowired AnalysisService analysisService;
  @Autowired com.pglens.server.trend.TrendService trends;
  @Autowired JdbcTemplate metadata;

  private long dbId;
  private ManagedChannel channel;
  private SampleCollector collector;
  private ValidationRunner validationRunner;

  @BeforeAll
  static void seedMonitored() {
    ConnectionTarget target =
        new ConnectionTarget(
            MONITORED.getJdbcUrl(),
            MONITORED.getUsername(),
            MONITORED.getPassword(),
            "pglens_demo");
    su = new JdbcTemplate(DataSources.forScan(target));
    su.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
    su.execute("CREATE EXTENSION IF NOT EXISTS hypopg");
    // A single unindexed, selective column so the planner seq-scans it and HypoPG validates a
    // btree.
    su.execute(
        "CREATE TABLE orders (id serial PRIMARY KEY, customer_id int NOT NULL, "
            + "status text NOT NULL, total_cents int NOT NULL, created_at timestamptz NOT NULL)");
    su.execute(
        "INSERT INTO orders (customer_id, status, total_cents, created_at) "
            + "SELECT (random() * 999 + 1)::int, 'completed', (random() * 10000)::int, "
            + "now() - (g || ' minutes')::interval FROM generate_series(1, 20000) g");
    su.execute("ANALYZE orders");

    // The agent's own monitored connection (separate from su), and the engine db-half readers over
    // it.
    agentDs = DataSources.forScan(target);
    agentJdbc = new JdbcTemplate(agentDs);
    statsReader = new StatsReader(agentJdbc);
    catalogReader = new CatalogReader(agentJdbc);
    planCapturer = new PlanCapturer(agentJdbc);
    validator = new HypoPGValidator(agentJdbc);
  }

  @BeforeEach
  void setUp() {
    metadata.execute(
        "TRUNCATE monitored_dbs, query_texts, query_cumulative, query_stats, table_catalog, "
            + "index_catalog, index_stats, validation_jobs, recommendations, index_hygiene "
            + "RESTART IDENTITY CASCADE");
    dbId = monitoredDbs.register(DB_NAME, "monitored-db", Tokens.sha256Hex(TOKEN));
    su.execute("SELECT pg_stat_statements_reset()");

    PglensAgentProperties props = new PglensAgentProperties();
    props.setDbName(DB_NAME);
    props.setToken(TOKEN);
    props.getSample().setTopN(200);
    props.getSample().setMinCalls(1);
    props.getValidation().setMaxLease(10);

    channel =
        ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort()).usePlaintext().build();
    collector =
        new SampleCollector(
            props,
            agentJdbc,
            agentDs,
            statsReader,
            catalogReader,
            planCapturer,
            new IngestClient(channel, TOKEN));
    validationRunner =
        new ValidationRunner(props, agentJdbc, validator, new ValidationClient(channel, TOKEN));
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    if (channel != null) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void agentSamplesPersistDeltasAndTheTrendQueryReturns() {
    warm(5);
    collector.sample(); // anchor — no delta row yet
    warm(5);
    collector.sample(); // the counters advanced → one delta row

    long queryid = orderQueryId();
    assertThat(statsRowsFor(queryid)).isGreaterThanOrEqualTo(1);

    QueryTrend series =
        trends.series(
            dbId, queryid, Instant.now().minus(Duration.ofHours(1)), Instant.now().plusSeconds(60));
    assertThat(series.points()).isNotEmpty();
    assertThat(series.points().get(0).totalExecTimeDeltaMs()).isGreaterThan(0.0);
    assertThat(series.normalizedText()).contains("customer_id");

    // The cross-query top-movers path (the dogfood scan) also surfaces it — a first-seen load.
    assertThat(trends.topMovers(dbId, Instant.now().plusSeconds(5), Duration.ofDays(7), 10))
        .extracting(TopMover::queryid)
        .contains(queryid);
  }

  @Test
  void aStatsResetBetweenSamplesKeepsTheDeltaCorrect() {
    warm(5);
    collector.sample(); // anchor at cumulative 5
    su.execute("SELECT pg_stat_statements_reset()"); // counters restart
    warm(3);
    collector.sample(); // later stats_reset → delta is the post-reset cumulative (3), not 3-5

    long queryid = orderQueryId();
    assertThat(
            metadata.queryForObject(
                "SELECT calls_delta FROM query_stats WHERE queryid = ?", Long.class, queryid))
        .isEqualTo(3L);
  }

  @Test
  void aValidationJobRoundTripsIntoAValidatedRecommendation() {
    warm(10);
    collector.sample(); // persists the plan-captured query + the catalog for detection

    int enqueued = analysisService.run(); // detects the seq scan → enqueues a btree candidate
    assertThat(enqueued).isGreaterThanOrEqualTo(1);

    validationRunner.validatePending(); // agent leases → edge HypoPG → reports the verdict

    long queryid = orderQueryId();
    assertThat(
            metadata.queryForObject(
                "SELECT count(*) FROM recommendations WHERE queryid = ? AND status = "
                    + "'PLANNER_VALIDATED'",
                Integer.class,
                queryid))
        .isGreaterThanOrEqualTo(1);
    Double before =
        metadata.queryForObject(
            "SELECT before_cost FROM recommendations WHERE queryid = ? AND status = "
                + "'PLANNER_VALIDATED' LIMIT 1",
            Double.class,
            queryid);
    Double after =
        metadata.queryForObject(
            "SELECT after_cost FROM recommendations WHERE queryid = ? AND status = "
                + "'PLANNER_VALIDATED' LIMIT 1",
            Double.class,
            queryid);
    assertThat(after).isLessThan(before); // a real, labeled generic-plan cost drop
  }

  @Test
  void anUnknownTokenIsRejected() throws InterruptedException {
    ManagedChannel bad =
        ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort()).usePlaintext().build();
    try {
      IngestClient badClient = new IngestClient(bad, "wrong-token");
      SampleBatch batch =
          SampleBatch.newBuilder()
              .setDbName(DB_NAME)
              .setAgentSampleEpochMs(System.currentTimeMillis())
              .build();
      assertThatThrownBy(() -> badClient.send(batch, 10))
          .isInstanceOf(IngestClient.IngestException.class)
          .hasStackTraceContaining("UNAUTHENTICATED");
    } finally {
      bad.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  // --- helpers ---

  /** Runs the selective query {@code n} times so pg_stat_statements accumulates its stats. */
  private void warm(int n) {
    for (int i = 0; i < n; i++) {
      su.queryForList("SELECT * FROM orders WHERE customer_id = 42");
    }
  }

  private long orderQueryId() {
    return metadata.queryForObject(
        "SELECT queryid FROM query_texts WHERE normalized_text LIKE '%customer_id%' "
            + "ORDER BY queryid LIMIT 1",
        Long.class);
  }

  private Integer statsRowsFor(long queryid) {
    return metadata.queryForObject(
        "SELECT count(*) FROM query_stats WHERE queryid = ?", Integer.class, queryid);
  }
}
