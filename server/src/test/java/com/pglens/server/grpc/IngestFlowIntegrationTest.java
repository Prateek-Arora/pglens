package com.pglens.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pglens.proto.v1.IngestGrpc;
import com.pglens.proto.v1.IngestSummary;
import com.pglens.proto.v1.QueryStatSample;
import com.pglens.proto.v1.QueryText;
import com.pglens.proto.v1.SampleBatch;
import com.pglens.server.persistence.MonitoredDbRepository;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
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
 * End-to-end ingest over a real gRPC transport + real Postgres: a client streams cumulative
 * samples, the server authenticates the token, computes deltas against the last persisted snapshot,
 * and persists the time-series. Proves the DoD items "streams samples over gRPC", "deltas are
 * correct across intervals", and "unknown agents are rejected".
 */
@Tag("it")
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "pglens.grpc.port=0")
class IngestFlowIntegrationTest {

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

  private static final String TOKEN = "agent-secret-token";
  private static final long QUERYID = 111L;
  // Two distinct pg_stat_statements_info.stats_reset values; RESET_B is later than RESET_A.
  private static final long RESET_A = 1_000L;
  private static final long RESET_B = 2_000L;

  @Autowired GrpcServerLifecycle grpcServer;
  @Autowired MonitoredDbRepository monitoredDbs;
  @Autowired JdbcTemplate jdbc;

  private ManagedChannel channel;

  @BeforeEach
  void setUp() {
    jdbc.execute(
        "TRUNCATE monitored_dbs, query_texts, query_cumulative, query_stats RESTART IDENTITY CASCADE");
    monitoredDbs.register("demo", "monitored-db", Tokens.sha256Hex(TOKEN));
    channel = channelWithToken(TOKEN);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    if (channel != null) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void firstBatchAnchorsWithoutDeltasThenSecondBatchPersistsTheDelta() throws Exception {
    IngestSummary first = send(batch(1_000L, RESET_A, sample(100, 500.0, 1000), text()));
    assertThat(first.getAcceptedSamples()).isEqualTo(1);
    assertThat(first.getAcceptedTexts()).isEqualTo(1);
    // First sample only establishes the anchor — no delta row yet.
    assertThat(countStats()).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM query_texts", Integer.class)).isEqualTo(1);

    // Second interval, no reset: cumulative advanced by (50 calls, 300ms, 600 rows).
    send(batch(1_060L, RESET_A, sample(150, 800.0, 1600)));

    assertThat(countStats()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT calls_delta FROM query_stats", Long.class))
        .isEqualTo(50L);
    assertThat(
            jdbc.queryForObject("SELECT total_exec_time_delta_ms FROM query_stats", Double.class))
        .isCloseTo(300.0, org.assertj.core.api.Assertions.within(1e-9));
    assertThat(jdbc.queryForObject("SELECT rows_delta FROM query_stats", Long.class))
        .isEqualTo(600L);
    assertThat(jdbc.queryForObject("SELECT mean_exec_time_ms FROM query_stats", Double.class))
        .isCloseTo(6.0, org.assertj.core.api.Assertions.within(1e-9));
  }

  @Test
  void statsResetBetweenBatchesMakesTheDeltaThePostResetCumulative() throws Exception {
    // Anchor at cumulative 100.
    send(batch(1_000L, RESET_A, sample(100, 500.0, 1000)));
    // Next batch reports a LATER stats_reset (a pg_stat_statements_reset() happened): counters
    // restarted to 30. The delta must be 30 (post-reset activity), not 30 - 100 = negative.
    send(batch(1_060L, RESET_B, sample(30, 120.0, 400)));

    assertThat(countStats()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT calls_delta FROM query_stats", Long.class))
        .isEqualTo(30L);
    assertThat(jdbc.queryForObject("SELECT rows_delta FROM query_stats", Long.class))
        .isEqualTo(400L);
  }

  @Test
  void aDroppedSampleLosesNoActivityInTheNextDelta() throws Exception {
    // The §10 no-lost-window self-check (ADR-0024), distinct from the reset path above: this is the
    // "agent survives server downtime without losing a window" DoD item.
    //
    // Batch A anchors at cumulative 100 (first sample, no delta row).
    send(batch(1_000L, RESET_A, sample(100, 500.0, 1000), text()));
    assertThat(countStats()).isZero();

    // The agent samples again at cumulative 130 but that SEND IS LOST (a failed send / the server
    // was down) — the server never receives it, so nothing advances the anchor off 100. We model
    // that simply by NOT sending that batch.

    // The next successful batch reports cumulative 180. Because the server anchors deltas to the
    // last PERSISTED snapshot (100), not to any single send, this one delta spans the WHOLE gap:
    // calls 180-100 = 80 — the 30 calls in the dropped sample are included, not lost, and (because
    // the stateless agent always resamples fresh rather than replaying) not double-counted.
    send(batch(1_120L, RESET_A, sample(180, 1400.0, 2000)));

    assertThat(countStats()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT calls_delta FROM query_stats", Long.class))
        .isEqualTo(80L);
    assertThat(
            jdbc.queryForObject("SELECT total_exec_time_delta_ms FROM query_stats", Double.class))
        .isCloseTo(900.0, org.assertj.core.api.Assertions.within(1e-9));
    assertThat(jdbc.queryForObject("SELECT rows_delta FROM query_stats", Long.class))
        .isEqualTo(1000L);
  }

  @Test
  void unknownTokenIsRejected() throws InterruptedException {
    ManagedChannel bad = channelWithToken("wrong-token");
    try {
      assertThatThrownBy(() -> send(bad, batch(1_000L, RESET_A, sample(100, 500.0, 1000))))
          .isInstanceOf(StatusRuntimeException.class)
          .hasMessageContaining("UNAUTHENTICATED");
    } finally {
      bad.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  // --- helpers ---

  private Integer countStats() {
    return jdbc.queryForObject("SELECT count(*) FROM query_stats", Integer.class);
  }

  private ManagedChannel channelWithToken(String token) {
    Metadata md = new Metadata();
    md.put(AuthInterceptor.TOKEN_HEADER, token);
    return ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort())
        .usePlaintext()
        .intercept(MetadataUtils.newAttachHeadersInterceptor(md))
        .build();
  }

  private IngestSummary send(SampleBatch batch) throws Exception {
    return send(channel, batch);
  }

  private static IngestSummary send(ManagedChannel channel, SampleBatch batch) throws Exception {
    IngestGrpc.IngestStub stub = IngestGrpc.newStub(channel);
    AtomicReference<IngestSummary> summary = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    StreamObserver<SampleBatch> request =
        stub.streamSnapshot(
            new StreamObserver<>() {
              @Override
              public void onNext(IngestSummary value) {
                summary.set(value);
              }

              @Override
              public void onError(Throwable t) {
                error.set(t);
                done.countDown();
              }

              @Override
              public void onCompleted() {
                done.countDown();
              }
            });
    request.onNext(batch);
    request.onCompleted();
    if (!done.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("ingest did not complete in time");
    }
    if (error.get() != null) {
      // Surface the gRPC StatusRuntimeException as-is so callers can assert on its status.
      if (error.get() instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException(error.get());
    }
    return summary.get();
  }

  private static SampleBatch batch(
      long agentEpochMs, long statsResetMs, QueryStatSample sample, QueryText... texts) {
    SampleBatch.Builder b =
        SampleBatch.newBuilder()
            .setDbName("demo")
            .setAgentSampleEpochMs(agentEpochMs)
            .setStatsResetEpochMs(statsResetMs)
            .addSamples(sample);
    for (QueryText t : texts) {
      b.addNewTexts(t);
    }
    return b.build();
  }

  private static QueryStatSample sample(long calls, double totalMs, long rows) {
    return QueryStatSample.newBuilder()
        .setQueryid(QUERYID)
        .setTextHash("h1")
        .setCalls(calls)
        .setTotalExecTimeMs(totalMs)
        .setRows(rows)
        .build();
  }

  private static QueryText text() {
    return QueryText.newBuilder()
        .setQueryid(QUERYID)
        .setTextHash("h1")
        .setNormalizedText("select * from orders where id = $1")
        .setPlanCaptured(false)
        .build();
  }
}
