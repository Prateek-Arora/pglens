package com.pglens.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.proto.v1.CatalogSnapshot;
import com.pglens.proto.v1.IndexStat;
import com.pglens.proto.v1.TableStat;
import com.pglens.server.grpc.Tokens;
import com.pglens.server.persistence.CatalogRepository;
import com.pglens.server.persistence.MonitoredDbRepository;
import java.util.List;
import java.util.Map;
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
 * The scheduled analysis job (ADR-0028) over a real metadata Postgres: it rebuilds the persisted
 * catalog, runs the pure detector on a captured plan, and enqueues the surviving candidate as a
 * PENDING validation job — idempotently. Also proves the catalog persist→reload round-trip that
 * feeds detection. The scheduler itself is parked (huge initial delay) so the test drives {@code
 * run()} directly and asserts exact counts.
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
class AnalysisFlowIntegrationTest {

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

  private static final long QUERYID = 5150L;
  // A real EXPLAIN (GENERIC_PLAN, JSON) plan: a Seq Scan on customers filtering an unindexed
  // column.
  // R1 flags it → a btree candidate on customers(email).
  private static final String SEQ_SCAN_PLAN =
      """
      [
        {
          "Plan": {
            "Node Type": "Seq Scan",
            "Relation Name": "customers",
            "Alias": "customers",
            "Startup Cost": 0.00,
            "Total Cost": 473.00,
            "Plan Rows": 1,
            "Plan Width": 39,
            "Output": ["id", "full_name", "email"],
            "Filter": "(customers.email = $1)"
          }
        }
      ]
      """;

  @Autowired AnalysisService analysisService;
  @Autowired CatalogRepository catalogs;
  @Autowired MonitoredDbRepository monitoredDbs;
  @Autowired JdbcTemplate jdbc;

  private long dbId;

  @BeforeEach
  void setUp() {
    jdbc.execute(
        "TRUNCATE monitored_dbs, query_texts, table_catalog, index_catalog, validation_jobs, "
            + "recommendations RESTART IDENTITY CASCADE");
    dbId = monitoredDbs.register("demo", "monitored-db", Tokens.sha256Hex("t"));
  }

  @Test
  void catalogPersistsAndReloadsForDetection() {
    catalogs.replaceCatalog(
        dbId,
        CatalogSnapshot.newBuilder()
            .addTables(TableStat.newBuilder().setTableName("customers").setEstRows(1_000_000))
            .addIndexes(
                IndexStat.newBuilder()
                    .setIndexName("customers_pkey")
                    .setTableName("customers")
                    .setIsUnique(true)
                    .setIsPrimary(true)
                    .setMethod("btree")
                    .addColumns("id"))
            .build());

    com.pglens.engine.model.CatalogSnapshot loaded = catalogs.load(dbId);
    assertThat(loaded.reltuples("customers")).isEqualTo(1_000_000);
    assertThat(loaded.hasIndexLeadingWith("customers", "id")).isTrue();
    assertThat(loaded.hasIndexLeadingWith("customers", "email")).isFalse();
  }

  @Test
  void analysisEnqueuesAValidationJobForAnUnindexedSeqScanFilter() {
    seedCapturedQuery();
    seedCatalog(/* withEmailIndex= */ false);

    int enqueued = analysisService.run();

    assertThat(enqueued).isEqualTo(1);
    List<Map<String, Object>> jobs = jdbc.queryForList("SELECT * FROM validation_jobs");
    assertThat(jobs).hasSize(1);
    Map<String, Object> job = jobs.get(0);
    assertThat(job.get("state")).isEqualTo("PENDING");
    assertThat(job.get("access_method")).isEqualTo("BTREE");
    assertThat(job.get("queryid")).isEqualTo(QUERYID);
    assertThat((String) job.get("candidate_ddl")).contains("customers").contains("email");
  }

  @Test
  void reRunningAnalysisDoesNotEnqueueADuplicate() {
    seedCapturedQuery();
    seedCatalog(false);

    assertThat(analysisService.run()).isEqualTo(1);
    // Second pass: the candidate is already in flight (PENDING) → the partial-unique arbiter blocks
    // a
    // duplicate. That the second run succeeds also proves the transaction-scoped advisory lock was
    // released after the first (ADR-0028).
    assertThat(analysisService.run()).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM validation_jobs", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void anExistingIndexOnTheFilterColumnYieldsNoCandidate() {
    seedCapturedQuery();
    seedCatalog(/* withEmailIndex= */ true);

    assertThat(analysisService.run()).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM validation_jobs", Integer.class)).isZero();
  }

  @Test
  void aCompletedCandidateIsNotReValidatedUntilTheCooldownElapses() {
    seedCapturedQuery();
    seedCatalog(false);
    assertThat(analysisService.run()).isEqualTo(1); // one PENDING job

    // The agent completes it: mark the job DONE and persist a fresh recommendation (as
    // ValidationRepository.recordResult would). The in-flight arbiter no longer blocks a re-enqueue
    // now that the job is DONE — only the F1 revalidate cooldown does.
    completePendingJobWithAFreshRecommendation();

    // A pass while the recommendation is fresh must NOT re-enqueue. Before F1, every pass appended
    // a
    // brand-new job for this already-validated candidate the instant its prior job went DONE —
    // growing validation_jobs unboundedly and re-running edge HypoPG every tick.
    assertThat(analysisService.run()).isZero();
    assertThat(pendingCount()).isZero();

    // Once the recommendation ages past the cooldown (default 1h), the next pass re-validates — so
    // a
    // stale verdict still gets refreshed (e.g. retracted after the user actually adds the index).
    jdbc.update("UPDATE recommendations SET updated_at = now() - interval '2 hours'");
    assertThat(analysisService.run()).isEqualTo(1);
    assertThat(pendingCount()).isEqualTo(1);
  }

  @Test
  void terminalJobsArePrunedAfterTheRetentionWindow() {
    seedCapturedQuery();
    seedCatalog(false);
    assertThat(analysisService.run()).isEqualTo(1);

    // Complete it (fresh rec blocks re-enqueue) then backdate its completion beyond the retention
    // window (default 7 days).
    completePendingJobWithAFreshRecommendation();
    jdbc.update(
        "UPDATE validation_jobs SET completed_at = now() - interval '8 days' WHERE state = 'DONE'");

    // The next pass prunes terminal jobs older than the retention window; the fresh recommendation
    // keeps the candidate from being re-enqueued, so the queue ends empty (F1 — bounded growth).
    analysisService.run();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM validation_jobs", Integer.class)).isZero();
  }

  @Test
  void aTimedOutLeaseIsReclaimedToPendingAndBumpsAttempts() {
    // A job leased 10 min ago — past the 5-min default lease timeout — with the agent having
    // crashed
    // before reporting (ADR-0035). The reclaim in the singleton pass returns it to PENDING.
    insertLeasedJob("now() - interval '10 minutes'", /* attempts= */ 0);

    assertThat(analysisService.run()).isZero(); // reclaim is not an enqueue

    Map<String, Object> job = jdbc.queryForMap("SELECT * FROM validation_jobs");
    assertThat(job.get("state")).isEqualTo("PENDING");
    assertThat(job.get("attempts")).isEqualTo(1);
    assertThat(job.get("leased_at")).isNull();
  }

  @Test
  void aLeaseWithinTheTimeoutIsNotReclaimed() {
    // A freshly-leased job (a slow-but-live validation the agent is still working) must not be
    // reclaimed out from under it — the timeout is set well past the longest legitimate batch.
    insertLeasedJob("now()", /* attempts= */ 0);

    analysisService.run();

    Map<String, Object> job = jdbc.queryForMap("SELECT * FROM validation_jobs");
    assertThat(job.get("state")).isEqualTo("LEASED");
    assertThat(job.get("attempts")).isEqualTo(0);
  }

  @Test
  void aLeaseReclaimedPastMaxAttemptsIsDeadLettered() {
    // A candidate whose validation keeps throwing (a genuinely-poison DDL) must not reclaim-loop
    // forever: past the max-attempts cap the timed-out lease is dead-lettered to FAILED, not reset
    // to PENDING. attempts=10 is safely over the default cap of 5.
    insertLeasedJob("now() - interval '10 minutes'", /* attempts= */ 10);

    analysisService.run();

    Map<String, Object> job = jdbc.queryForMap("SELECT * FROM validation_jobs");
    assertThat(job.get("state")).isEqualTo("FAILED");
    assertThat((String) job.get("reason")).contains("dead-lettered");
  }

  @Test
  void aRecentlyDeadLetteredCandidateIsNotReEnqueuedUntilTheCooldown() {
    seedCapturedQuery();
    seedCatalog(false);
    assertThat(analysisService.run()).isEqualTo(1); // the real candidate enqueued

    // Simulate reclaim dead-lettering it (poison candidate): FAILED with a fresh completion.
    jdbc.update(
        "UPDATE validation_jobs SET state = 'FAILED', completed_at = now(), "
            + "reason = 'dead-lettered: exceeded max validation attempts' WHERE state = 'PENDING'");

    // While the FAILED job is fresh, the poison cooldown blocks a re-enqueue — no churn loop
    // (ADR-0035). Without it, every pass would immediately re-enqueue the same throwing candidate.
    assertThat(analysisService.run()).isZero();
    assertThat(pendingCount()).isZero();

    // Once the FAILED job ages past the cooldown (default 1h), a fresh attempt IS enqueued — so a
    // transient failure (agent was momentarily down) still gets retried.
    jdbc.update(
        "UPDATE validation_jobs SET completed_at = now() - interval '2 hours' WHERE state = 'FAILED'");
    assertThat(analysisService.run()).isEqualTo(1);
    assertThat(pendingCount()).isEqualTo(1);
  }

  /**
   * Inserts one LEASED job for the reclaim tests; {@code leasedAtExpr} is a SQL time expression.
   */
  private void insertLeasedJob(String leasedAtExpr, int attempts) {
    jdbc.update(
        "INSERT INTO validation_jobs (db_id, queryid, normalized_sql, candidate_ddl, access_method, "
            + "state, leased_at, attempts) "
            + "VALUES (?, ?, 'sql', 'CREATE INDEX x ON customers (email);', 'BTREE', 'LEASED', "
            + leasedAtExpr
            + ", ?)",
        dbId,
        QUERYID,
        attempts);
  }

  /** Mimics a finished round-trip: mark the PENDING job DONE + persist a fresh recommendation. */
  private void completePendingJobWithAFreshRecommendation() {
    jdbc.update(
        "INSERT INTO recommendations (db_id, queryid, ddl, access_method, status) "
            + "SELECT db_id, queryid, candidate_ddl, access_method, 'PLANNER_VALIDATED' "
            + "FROM validation_jobs WHERE state = 'PENDING'");
    jdbc.update(
        "UPDATE validation_jobs SET state = 'DONE', completed_at = now() WHERE state = 'PENDING'");
  }

  private Integer pendingCount() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM validation_jobs WHERE state = 'PENDING'", Integer.class);
  }

  private void seedCapturedQuery() {
    jdbc.update(
        "INSERT INTO query_texts (db_id, queryid, text_hash, normalized_text, plan_json, "
            + "plan_captured) VALUES (?, ?, ?, ?, ?, true)",
        dbId,
        QUERYID,
        "h",
        "select id, full_name, email from customers where email = $1",
        SEQ_SCAN_PLAN);
  }

  private void seedCatalog(boolean withEmailIndex) {
    CatalogSnapshot.Builder catalog =
        CatalogSnapshot.newBuilder()
            .addTables(TableStat.newBuilder().setTableName("customers").setEstRows(1_000_000))
            .addIndexes(
                IndexStat.newBuilder()
                    .setIndexName("customers_pkey")
                    .setTableName("customers")
                    .setIsUnique(true)
                    .setIsPrimary(true)
                    .setMethod("btree")
                    .addColumns("id"));
    if (withEmailIndex) {
      catalog.addIndexes(
          IndexStat.newBuilder()
              .setIndexName("customers_email_idx")
              .setTableName("customers")
              .setMethod("btree")
              .addColumns("email"));
    }
    catalogs.replaceCatalog(dbId, catalog.build());
  }
}
