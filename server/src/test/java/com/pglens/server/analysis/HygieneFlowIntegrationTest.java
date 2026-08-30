package com.pglens.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.hygiene.IndexHygieneFinding;
import com.pglens.engine.hygiene.IndexHygieneFinding.Kind;
import com.pglens.proto.v1.CatalogSnapshot;
import com.pglens.proto.v1.IndexStat;
import com.pglens.proto.v1.TableStat;
import com.pglens.server.grpc.Tokens;
import com.pglens.server.persistence.CatalogRepository;
import com.pglens.server.persistence.HygieneRepository;
import com.pglens.server.persistence.MonitoredDbRepository;
import java.time.Instant;
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
 * The index-hygiene rule (B5 / ADR-0029) end to end over a real metadata Postgres: the persisted
 * index_catalog + the idx_scan history in index_stats drive the pure analyzer, and the scheduled
 * analysis replaces this db's findings. Proves the DoD "index-hygiene rec produced (unused +
 * duplicate) over the snapshot window; safe guard rails" — and above all that a guarded
 * (unique/PK/FK/constraint) index is never flagged, even when its own scan window is empty. The
 * scheduler is parked (huge initial delay); the test drives {@code run()} directly.
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
class HygieneFlowIntegrationTest {

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

  @Autowired AnalysisService analysisService;
  @Autowired CatalogRepository catalogs;
  @Autowired HygieneRepository hygiene;
  @Autowired MonitoredDbRepository monitoredDbs;
  @Autowired JdbcTemplate jdbc;

  private long dbId;

  @BeforeEach
  void setUp() {
    jdbc.execute(
        "TRUNCATE monitored_dbs, table_catalog, index_catalog, index_stats, index_hygiene "
            + "RESTART IDENTITY CASCADE");
    dbId = monitoredDbs.register("demo", "monitored-db", Tokens.sha256Hex("t"));
  }

  @Test
  void flagsDuplicateAndUnusedButNeverAGuardedIndex() {
    // orders has: a PK (guarded), two identical customer_id indexes (a duplicate pair), and an
    // index on total_cents that is never scanned.
    CatalogSnapshot catalog =
        CatalogSnapshot.newBuilder()
            .addTables(TableStat.newBuilder().setTableName("orders").setEstRows(100_000))
            .addIndexes(index("orders_pkey", true, true, true, "id"))
            .addIndexes(index("idx_orders_customer_a", false, false, false, "customer_id"))
            .addIndexes(index("idx_orders_customer_b", false, false, false, "customer_id"))
            .addIndexes(index("idx_orders_total", false, false, false, "total_cents"))
            .build();
    catalogs.replaceCatalog(dbId, catalog);

    // Two snapshots make a window. The PK's counter is FLAT (delta 0) too — so it would look
    // "unused", proving the guard (not the window) is what spares it. customer_a grows (it is the
    // surviving duplicate); customer_b and total stay flat.
    recordScans(Instant.now().minusSeconds(600), 10, 5, 5, 0);
    recordScans(Instant.now(), 10, 105, 5, 0);

    analysisService.run();

    List<IndexHygieneFinding> findings = hygiene.loadHygiene(dbId);
    assertThat(findings)
        .extracting(IndexHygieneFinding::indexName)
        .containsExactlyInAnyOrder("idx_orders_customer_b", "idx_orders_total")
        .doesNotContain("orders_pkey", "idx_orders_customer_a");

    IndexHygieneFinding dup = byName(findings, "idx_orders_customer_b");
    assertThat(dup.kind()).isEqualTo(Kind.DUPLICATE);
    assertThat(dup.relatedIndex()).isEqualTo("idx_orders_customer_a");

    IndexHygieneFinding unused = byName(findings, "idx_orders_total");
    assertThat(unused.kind()).isEqualTo(Kind.UNUSED);
    assertThat(unused.relatedIndex()).isNull();
    assertThat(unused.reason()).contains("No index scans");
  }

  @Test
  void reRunReplacesFindingsSoAResolvedDuplicateStopsBeingReported() {
    catalogs.replaceCatalog(
        dbId,
        CatalogSnapshot.newBuilder()
            .addTables(TableStat.newBuilder().setTableName("orders").setEstRows(100_000))
            .addIndexes(index("idx_orders_customer_a", false, false, false, "customer_id"))
            .addIndexes(index("idx_orders_customer_b", false, false, false, "customer_id"))
            .build());
    analysisService.run();
    assertThat(hygiene.loadHygiene(dbId)).hasSize(1); // the duplicate

    // The user drops the duplicate; the next catalog no longer has it. Re-analysis must clear the
    // stale finding (findings are derived state, replaced each pass).
    catalogs.replaceCatalog(
        dbId,
        CatalogSnapshot.newBuilder()
            .addTables(TableStat.newBuilder().setTableName("orders").setEstRows(100_000))
            .addIndexes(index("idx_orders_customer_a", false, false, false, "customer_id"))
            .build());
    analysisService.run();
    assertThat(hygiene.loadHygiene(dbId)).isEmpty();
  }

  @Test
  void aScanCounterResetIsNotMistakenForUnused() {
    catalogs.replaceCatalog(
        dbId,
        CatalogSnapshot.newBuilder()
            .addTables(TableStat.newBuilder().setTableName("orders").setEstRows(100_000))
            .addIndexes(index("idx_orders_total", false, false, false, "total_cents"))
            .build());
    // The counter went backwards (100 → 0): a stats reset, not zero usage. The window is
    // inconclusive, so the index must NOT be reported unused.
    recordScans(Instant.now().minusSeconds(600), 0, 0, 0, 100);
    recordScans(Instant.now(), 0, 0, 0, 0);

    analysisService.run();

    assertThat(hygiene.loadHygiene(dbId)).isEmpty();
  }

  // --- helpers ---

  private static IndexStat.Builder index(
      String name, boolean unique, boolean primary, boolean constraintBacked, String... columns) {
    IndexStat.Builder b =
        IndexStat.newBuilder()
            .setIndexName(name)
            .setTableName("orders")
            .setIsUnique(unique)
            .setIsPrimary(primary)
            .setConstraintBacked(constraintBacked)
            .setDefinition("CREATE INDEX " + name + " ON orders (...)")
            .setMethod("btree");
    for (String c : columns) {
      b.addColumns(c);
    }
    return b;
  }

  /** Appends one idx_scan snapshot at {@code at} for (pkey, customer_a, customer_b, total). */
  private void recordScans(Instant at, long pkey, long customerA, long customerB, long total) {
    CatalogSnapshot snapshot =
        CatalogSnapshot.newBuilder()
            .addIndexes(IndexStat.newBuilder().setIndexName("orders_pkey").setIdxScan(pkey))
            .addIndexes(
                IndexStat.newBuilder().setIndexName("idx_orders_customer_a").setIdxScan(customerA))
            .addIndexes(
                IndexStat.newBuilder().setIndexName("idx_orders_customer_b").setIdxScan(customerB))
            .addIndexes(IndexStat.newBuilder().setIndexName("idx_orders_total").setIdxScan(total))
            .build();
    catalogs.recordIndexScans(dbId, snapshot, at);
  }

  private static IndexHygieneFinding byName(List<IndexHygieneFinding> findings, String name) {
    return findings.stream()
        .filter(f -> f.indexName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no finding for " + name));
  }
}
