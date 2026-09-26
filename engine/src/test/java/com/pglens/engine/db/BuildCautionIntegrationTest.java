package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.ConnectionTarget;
import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.ValidationResult;
import com.pglens.engine.model.ValidationResult.Status;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The B-tree build caution against a real server (backlog B17, ADR-0041): a validated B-tree on a
 * text column of a table that stores long values out of line gets a caution — and the same index
 * really does fail to build — while short text and bounded columns get none. Catalog reads only,
 * under the read-only session guards.
 */
@Tag("it")
@Testcontainers
class BuildCautionIntegrationTest {

  @Container static final PostgreSQLContainer DB = MonitoredDbContainer.create();

  private static JdbcTemplate owner;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void seed() {
    MonitoredDbContainer.initSchema(DB); // extensions (hypopg, pg_stat_statements) + demo schema
    owner =
        new JdbcTemplate(
            DataSources.forScan(
                new ConnectionTarget(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), "t")));
    owner.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE");
    owner.execute(
        "CREATE TABLE notes (id serial PRIMARY KEY, author_id integer NOT NULL, body text)");
    owner.execute(
        "INSERT INTO notes (author_id, body) SELECT g % 500, 'note ' || g "
            + "FROM generate_series(1, 20000) g");
    // A few bodies of ~16 kB random hex: still over 2 kB compressed, so they go to TOAST.
    owner.execute(
        "INSERT INTO notes (author_id, body) "
            + "SELECT 1, (SELECT string_agg(md5(random()::text || g || i), '') "
            + "           FROM generate_series(1, 500) i) FROM generate_series(1, 5) g");
    owner.execute(
        "CREATE TABLE tags (id serial PRIMARY KEY, name text NOT NULL, weight integer NOT NULL)");
    owner.execute(
        "INSERT INTO tags (name, weight) SELECT 'tag-' || g, g % 100 "
            + "FROM generate_series(1, 20000) g");
    owner.execute("ANALYZE notes");
    owner.execute("ANALYZE tags");

    jdbc =
        new JdbcTemplate(
            DataSources.forScan(
                new ConnectionTarget(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), "t")));
    DataSources.applySessionGuards(jdbc);
  }

  private static ValidationResult validate(String sql, String table, String column) {
    IndexCandidate c =
        IndexCandidate.of(table, List.of(column), AccessMethod.BTREE, List.of("R1"), "test");
    return new HypoPGValidator(jdbc).validateDdl(sql, c.ddl(), AccessMethod.BTREE);
  }

  @Test
  void aTextKeyOnATableWithToastedValuesIsCautionedAndReallyFailsToBuild() {
    ValidationResult v = validate("SELECT * FROM notes WHERE body = $1", "notes", "body");

    assertThat(v.status()).isEqualTo(Status.PLANNER_VALIDATED);
    assertThat(v.buildCaution())
        .startsWith("Build caution: notes stores")
        .contains("any value of body is longer")
        .endsWith("SELECT max(pg_column_size(body)) FROM notes;");
    // The caution is real: HypoPG validated the index, but PostgreSQL won't build it.
    Throwable failure = null;
    try {
      owner.execute("CREATE INDEX notes_body_real ON notes (body)");
    } catch (RuntimeException e) {
      failure = e;
    }
    assertThat(failure).isNotNull();
    assertThat(failure.getMessage()).contains("index row");
  }

  @Test
  void aBoundedKeyOnTheSameToastedTableIsNotCautioned() {
    ValidationResult v = validate("SELECT * FROM notes WHERE author_id = $1", "notes", "author_id");

    assertThat(v.status()).isEqualTo(Status.PLANNER_VALIDATED);
    assertThat(v.buildCaution()).isNull();
  }

  @Test
  void aTextKeyOnATableWithNoToastDataIsNotCautioned() {
    ValidationResult v = validate("SELECT * FROM tags WHERE name = $1", "tags", "name");

    assertThat(v.status()).isEqualTo(Status.PLANNER_VALIDATED);
    assertThat(v.buildCaution()).isNull();
  }
}
