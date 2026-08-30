package com.pglens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FlywayMigrationIntegrationTest {

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
            "validation_jobs");
  }

  @Test
  void flywayAppliedTheMigrationsInOrder() {
    Integer applied =
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
    assertThat(applied).isEqualTo(4); // V1 + V2 + V3 + V4

    String version =
        jdbc.queryForObject(
            "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC "
                + "LIMIT 1",
            String.class);
    assertThat(version).isEqualTo("4");
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
