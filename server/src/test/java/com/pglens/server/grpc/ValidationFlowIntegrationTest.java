package com.pglens.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.pglens.proto.v1.LeaseRequest;
import com.pglens.proto.v1.ReportAck;
import com.pglens.proto.v1.ValidateRequest;
import com.pglens.proto.v1.ValidateResult;
import com.pglens.proto.v1.ValidationGrpc;
import com.pglens.proto.v1.ValidationStatus;
import com.pglens.server.persistence.MonitoredDbRepository;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
 * The a-pull validation round-trip over real gRPC + real Postgres (ADR-0023): an authenticated
 * agent leases a PENDING job (server-streaming), then reports a HypoPG verdict (client-streaming);
 * the server persists the recommendation with its evidence + ranking score and closes the job.
 * Proves the DoD item "stores recs with HypoPG-validated evidence via the Validation RPC
 * round-trip". The edge HypoPG itself is engine-tested; here the verdict is supplied to isolate the
 * transport + persistence.
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
class ValidationFlowIntegrationTest {

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
  private static final long QUERYID = 777L;
  private static final String DDL = "CREATE INDEX idx_orders_customer_id ON orders (customer_id);";

  @Autowired GrpcServerLifecycle grpcServer;
  @Autowired MonitoredDbRepository monitoredDbs;
  @Autowired JdbcTemplate jdbc;

  private long dbId;
  private ManagedChannel channel;

  @BeforeEach
  void setUp() {
    jdbc.execute(
        "TRUNCATE monitored_dbs, query_cumulative, validation_jobs, recommendations "
            + "RESTART IDENTITY CASCADE");
    dbId = monitoredDbs.register("demo", "monitored-db", Tokens.sha256Hex(TOKEN));
    // A measured total time so the ranking score is real: estimated = relative_drop × total.
    jdbc.update(
        "INSERT INTO query_cumulative (db_id, queryid, calls, total_exec_time_ms, rows, "
            + "shared_blks_hit, shared_blks_read, captured_at) VALUES (?, ?, 100, 5000, 0, 0, 0, now())",
        dbId,
        QUERYID);
    jdbc.update(
        "INSERT INTO validation_jobs (db_id, queryid, normalized_sql, candidate_ddl, access_method) "
            + "VALUES (?, ?, ?, ?, 'BTREE')",
        dbId,
        QUERYID,
        "select * from orders where customer_id = $1",
        DDL);
    channel = channelWithToken(TOKEN);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    if (channel != null) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void leaseThenReportPersistsAValidatedRecommendationAndClosesTheJob() throws Exception {
    List<ValidateRequest> leased = lease(channel, 10);
    assertThat(leased).hasSize(1);
    ValidateRequest job = leased.get(0);
    assertThat(job.getCandidateDdl()).isEqualTo(DDL);
    assertThat(job.getAccessMethod()).isEqualTo("BTREE");
    // The lease flipped the job to LEASED.
    assertThat(jobState(job.getJobId())).isEqualTo("LEASED");

    ReportAck ack =
        report(
            channel,
            ValidateResult.newBuilder()
                .setJobId(job.getJobId())
                .setStatus(ValidationStatus.PLANNER_VALIDATED)
                .setBeforeCost(1000.0)
                .setAfterCost(400.0)
                .setRelativeDrop(0.6)
                .setUsed(true)
                .setReason("Planner-validated (HypoPG estimate).")
                .build());
    assertThat(ack.getAccepted()).isEqualTo(1);

    // Recommendation persisted with evidence + ranking score = 0.6 × 5000 = 3000.
    Map<String, Object> rec =
        jdbc.queryForMap(
            "SELECT * FROM recommendations WHERE db_id = ? AND queryid = ?", dbId, QUERYID);
    assertThat(rec.get("status")).isEqualTo("PLANNER_VALIDATED");
    assertThat(rec.get("ddl")).isEqualTo(DDL);
    assertThat((Double) rec.get("relative_drop")).isCloseTo(0.6, within(1e-9));
    assertThat((Double) rec.get("estimated_ms_saved")).isCloseTo(3000.0, within(1e-6));
    assertThat((Boolean) rec.get("used")).isTrue();
    // Job closed.
    assertThat(jobState(job.getJobId())).isEqualTo("DONE");
  }

  @Test
  void aNotValidatedVerdictStoresNullCostsNeverAFabricatedZero() throws Exception {
    long jobId = lease(channel, 10).get(0).getJobId();

    report(
        channel,
        ValidateResult.newBuilder()
            .setJobId(jobId)
            .setStatus(ValidationStatus.NOT_PLANNER_VALIDATED)
            .setUsed(false)
            .setReason("Not planner-validated — GIN.")
            .build()); // before/after/relative deliberately unset

    Map<String, Object> rec =
        jdbc.queryForMap(
            "SELECT * FROM recommendations WHERE db_id = ? AND queryid = ?", dbId, QUERYID);
    assertThat(rec.get("status")).isEqualTo("NOT_PLANNER_VALIDATED");
    assertThat(rec.get("before_cost")).isNull();
    assertThat(rec.get("after_cost")).isNull();
    assertThat(rec.get("relative_drop")).isNull();
    assertThat(rec.get("estimated_ms_saved")).isNull();
  }

  @Test
  void unknownTokenCannotLease() {
    ManagedChannel bad = channelWithToken("wrong-token");
    try {
      assertThatThrownBy(() -> lease(bad, 10))
          .isInstanceOf(StatusRuntimeException.class)
          .hasMessageContaining("UNAUTHENTICATED");
    } finally {
      bad.shutdownNow();
    }
  }

  // --- helpers ---

  private String jobState(long jobId) {
    return jdbc.queryForObject(
        "SELECT state FROM validation_jobs WHERE id = ?", String.class, jobId);
  }

  private static List<ValidateRequest> lease(ManagedChannel channel, int max) {
    Iterator<ValidateRequest> it =
        ValidationGrpc.newBlockingStub(channel)
            .leaseValidations(LeaseRequest.newBuilder().setDbName("demo").setMax(max).build());
    List<ValidateRequest> jobs = new ArrayList<>();
    it.forEachRemaining(jobs::add);
    return jobs;
  }

  private static ReportAck report(ManagedChannel channel, ValidateResult result) throws Exception {
    AtomicReference<ReportAck> ack = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    StreamObserver<ValidateResult> request =
        ValidationGrpc.newStub(channel)
            .reportValidations(
                new StreamObserver<>() {
                  @Override
                  public void onNext(ReportAck value) {
                    ack.set(value);
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
    request.onNext(result);
    request.onCompleted();
    if (!done.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("report did not complete in time");
    }
    if (error.get() != null) {
      throw new RuntimeException(error.get());
    }
    return ack.get();
  }

  private ManagedChannel channelWithToken(String token) {
    Metadata md = new Metadata();
    md.put(AuthInterceptor.TOKEN_HEADER, token);
    return ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort())
        .usePlaintext()
        .intercept(MetadataUtils.newAttachHeadersInterceptor(md))
        .build();
  }
}
