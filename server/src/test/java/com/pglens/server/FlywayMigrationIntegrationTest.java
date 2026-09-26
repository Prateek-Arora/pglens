package com.pglens.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.server.grpc.GrpcTestTls;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the DoD item "migrations run on boot": booting the Spring context against a real
 * Postgres+pgvector container (the same image the compose metadata-db uses) runs Flyway, which must
 * create the full schema (V1 + the V2 catalog tables + the V3 index-hygiene table). Also asserts
 * the migrations applied in order, that the deliberate partial indexes on the validation queue
 * exist, and that the V4 dogfood trend index (BRIN on {@code query_stats.captured_at}, ADR-0033) is
 * present.
 *
 * <p>{@code @Tag("it")} — needs Docker; runs in the {@code integrationTest} task, not the unit
 * loop.
 */
@Tag("it")
@Testcontainers
// Port 0: an OS-assigned gRPC port, so the test never collides with a running stack on 9090.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "pglens.grpc.port=0")
class FlywayMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer METADATA =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:0.8.6-pg16")
              .asCompatibleSubstituteFor("postgres"));

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", METADATA::getJdbcUrl);
    registry.add("spring.datasource.username", METADATA::getUsername);
    registry.add("spring.datasource.password", METADATA::getPassword);
    GrpcTestTls.register(registry);
  }

  @Autowired JdbcTemplate jdbc;

  @Test
  void migrationsCreateTheFullSchema() {
    List<String> tables =
        jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' ORDER BY table_name",
            String.class);

    assertThat(tables)
        .contains(
            "monitored_dbs",
            "query_texts",
            "query_cumulative",
            "query_stats",
            "table_catalog", // V2
            "index_catalog",
            "index_stats",
            "index_hygiene", // V3
            "recommendations",
            "validation_jobs",
            "table_stats", // V6
            "knowledge_chunks", // V8
            "explanations",
            "users", // V9
            "sessions",
            "api_tokens",
            "query_stats_hourly"); // V10
  }

  @Test
  void flywayAppliedTheMigrationsInOrder() {
    Integer applied =
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
    assertThat(applied).isEqualTo(10); // V1 … V10

    String version =
        jdbc.queryForObject(
            "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC "
                + "LIMIT 1",
            String.class);
    assertThat(version).isEqualTo("10");
  }

  /**
   * V10 (ADR-0045): the hourly rollup equals the raw deltas' sums per UTC hour, whatever the insert
   * path — several rows at once, one at a time, and a within-statement duplicate that query_stats'
   * {@code ON CONFLICT DO NOTHING} skips (it must not be counted either).
   */
  @Test
  void theHourlyRollupAlwaysMatchesTheRawDeltas() {
    long db =
        jdbc.queryForObject(
            "INSERT INTO monitored_dbs (name, host, agent_token_hash) "
                + "VALUES ('rollup-test', 'h', 'x') RETURNING id",
            Long.class);
    try {
      String insert =
          "INSERT INTO query_stats (db_id, queryid, captured_at, agent_sample_at, calls_delta, "
              + "total_exec_time_delta_ms, mean_exec_time_ms, rows_delta, shared_blks_hit_delta, "
              + "shared_blks_read_delta) VALUES %s ON CONFLICT DO NOTHING";
      String row = "(%d, %d, TIMESTAMPTZ '%s', now(), %d, %s, 1, 1, 1, 1)";
      jdbc.update(
          insert.formatted(
              String.join(
                  ", ",
                  row.formatted(db, 7, "2026-09-26 10:05:00+00", 10, "25.0"),
                  row.formatted(db, 7, "2026-09-26 10:55:00+00", 5, "5.0"),
                  row.formatted(db, 7, "2026-09-26 11:00:00+00", 1, "2.0"),
                  row.formatted(db, 8, "2026-09-26 10:05:00+00", 3, "3.0"))));
      jdbc.update( // one more sample in 10:00, and a duplicate of an existing row
          insert.formatted(
              String.join(
                  ", ",
                  row.formatted(db, 7, "2026-09-26 10:30:00+05:30", 2, "4.0"), // 05:00 UTC
                  row.formatted(db, 7, "2026-09-26 10:20:00+00", 2, "4.0"),
                  row.formatted(db, 7, "2026-09-26 10:05:00+00", 99, "99.0"))));

      List<String> raw =
          jdbc.queryForList(
              "SELECT queryid || '@' || date_trunc('hour', captured_at, 'UTC') || '=' "
                  + "|| sum(calls_delta) || '/' || sum(total_exec_time_delta_ms) FROM query_stats "
                  + "WHERE db_id = ? GROUP BY queryid, date_trunc('hour', captured_at, 'UTC') "
                  + "ORDER BY 1",
              String.class,
              db);
      List<String> rollup =
          jdbc.queryForList(
              "SELECT queryid || '@' || hour || '=' || calls || '/' || total_exec_time_ms "
                  + "FROM query_stats_hourly WHERE db_id = ? ORDER BY 1",
              String.class,
              db);
      assertThat(rollup).isEqualTo(raw).hasSize(4);
      assertThat(
              jdbc.queryForObject(
                  "SELECT calls FROM query_stats_hourly WHERE db_id = ? AND queryid = 7 "
                      + "AND hour = '2026-09-26 10:00:00+00'",
                  Long.class,
                  db))
          .isEqualTo(17); // 10 + 5 + 2; the duplicate 99 was skipped
    } finally {
      jdbc.update("DELETE FROM monitored_dbs WHERE id = ?", db); // cascades to both tables
    }
  }

  /** V9 (ADR-0044): login is case-insensitive, so usernames are unique case-insensitively. */
  @Test
  void usernamesAreUniqueIgnoringCase() {
    String def =
        jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'users_username_lower_key'",
            String.class);
    assertThat(def).contains("UNIQUE").contains("lower(username)");
  }

  /** V8 (ADR-0043): pgvector + an HNSW cosine index over the docs passages. */
  @Test
  void knowledgeChunksHaveAnHnswCosineIndex() {
    String def =
        jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'knowledge_chunks_embedding_hnsw'",
            String.class);
    assertThat(def).contains("USING hnsw").contains("vector_cosine_ops");
    assertThat(
            jdbc.queryForObject(
                "SELECT format_type(atttypid, atttypmod) FROM pg_attribute "
                    + "WHERE attrelid = 'knowledge_chunks'::regclass AND attname = 'embedding'",
                String.class))
        .isEqualTo("vector(768)");
  }

  /** V7 (ADR-0041, B17): the build-caution column on recommendations. */
  @Test
  void recommendationsHaveABuildCautionColumn() {
    List<String> columns =
        jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'recommendations'",
            String.class);
    assertThat(columns).contains("build_caution");
  }

  /**
   * V5 (ADR-0035): the lease-reclaim column + the partial index that keeps the reclaim scan tight.
   */
  @Test
  void validationJobAttemptsColumnAndLeasedIndexExist() {
    List<String> columns =
        jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'validation_jobs'",
            String.class);
    assertThat(columns).contains("attempts");

    List<String> indexes =
        jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'validation_jobs'", String.class);
    assertThat(indexes).contains("validation_jobs_leased_idx");
  }

  @Test
  void validationQueuePartialIndexesExist() {
    List<String> indexes =
        jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'validation_jobs'", String.class);
    assertThat(indexes).contains("validation_jobs_pending_idx", "validation_jobs_inflight_uniq");
  }

  /** V4 (ADR-0033): the dogfood trend index is a BRIN on query_stats.captured_at. */
  @Test
  void trendBrinIndexExistsOnQueryStats() {
    String method =
        jdbc.queryForObject(
            "SELECT am.amname FROM pg_index i "
                + "JOIN pg_class c ON c.oid = i.indexrelid "
                + "JOIN pg_am am ON am.oid = c.relam "
                + "WHERE c.relname = 'query_stats_captured_brin'",
            String.class);
    assertThat(method).isEqualTo("brin");
  }
}
