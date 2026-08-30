package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.model.ConnectionTarget;
import com.pglens.engine.model.RankBy;
import com.pglens.engine.model.StatementStat;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link StatsReader} against the real monitored image. It resets {@code
 * pg_stat_statements} and seeds a workload with <em>fixed</em> call counts, so every assertion is
 * on deterministic values (call counts, presence, ordering-by-calls) — never on wall-clock timings,
 * which would be flaky in CI. Covers ranking, the {@code --min-calls} floor, the {@code LIMIT} cap,
 * and the utility/self hygiene filters (ADR-0014).
 */
@Tag("it")
@Testcontainers
class StatsReaderIntegrationTest {

  @Container static final PostgreSQLContainer<?> DB = MonitoredDbContainer.create();

  // Normalized-text fragments that identify the two SELECT markers we seed (constants collapse to
  // $1, so each shape is a single pg_stat_statements row).
  private static final String MARKER_FREQUENT = "from orders where status"; // seeded 5x
  private static final String MARKER_RARE = "from customers where country"; // seeded 2x

  private static StatsReader reader;

  @BeforeAll
  static void seedWorkload() {
    MonitoredDbContainer.initSchema(DB);
    ConnectionTarget target =
        new ConnectionTarget(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), "pglens_demo");
    JdbcTemplate jdbc = new JdbcTemplate(DataSources.forScan(target));

    // Deterministic baseline: forget the schema-creation statements the initdb scripts recorded.
    jdbc.execute("SELECT pg_stat_statements_reset()");

    // Two markers with fixed, distinct call counts. Identical text each time → one row per shape.
    for (int i = 0; i < 5; i++) {
      jdbc.queryForObject("SELECT count(*) FROM orders WHERE status = 'shipped'", Long.class);
    }
    for (int i = 0; i < 2; i++) {
      jdbc.queryForObject("SELECT count(*) FROM customers WHERE country = 'US'", Long.class);
    }

    // Noise that MUST be filtered out of the leaderboard: a utility statement, PgLens's own
    // pg_stat_statements/hypopg queries, and a marked catalog-introspection query (ADR-0021 —
    // pg_class
    // is not otherwise excluded, so the marker is what keeps it off PgLens's own leaderboard).
    jdbc.execute("SET statement_timeout = '9s'");
    jdbc.queryForObject("SELECT count(*) FROM pg_stat_statements", Long.class);
    jdbc.queryForObject("SELECT count(*) FROM hypopg()", Long.class);
    for (int i = 0; i < 3; i++) {
      jdbc.queryForObject(
          DataSources.INTROSPECTION_MARKER + "SELECT count(*) FROM pg_class WHERE relkind = 'r'",
          Long.class);
    }

    reader = new StatsReader(jdbc);
  }

  @Test
  void readsMarkersWithTheirExactCallCounts() {
    List<StatementStat> rows = reader.topStatements(RankBy.CALLS, 50, 1);
    assertThat(find(rows, MARKER_FREQUENT)).get().extracting(StatementStat::calls).isEqualTo(5L);
    assertThat(find(rows, MARKER_RARE)).get().extracting(StatementStat::calls).isEqualTo(2L);
  }

  @Test
  void minCallsExcludesStatementsBelowTheFloor() {
    List<StatementStat> rows = reader.topStatements(RankBy.CALLS, 50, 5);
    assertThat(find(rows, MARKER_FREQUENT)).as("5 calls ≥ floor of 5").isPresent();
    assertThat(find(rows, MARKER_RARE)).as("2 calls < floor of 5").isEmpty();
  }

  @Test
  void ranksByCallsDescending() {
    List<StatementStat> rows = reader.topStatements(RankBy.CALLS, 50, 1);
    assertThat(rows).isSortedAccordingTo((a, b) -> Long.compare(b.calls(), a.calls()));
    assertThat(indexOf(rows, MARKER_FREQUENT))
        .as("the 5x marker outranks the 2x marker")
        .isLessThan(indexOf(rows, MARKER_RARE));
  }

  @Test
  void filtersUtilityAndPgLensOwnStatements() {
    List<StatementStat> rows = reader.topStatements(RankBy.CALLS, 50, 1);
    assertThat(rows)
        .noneMatch(s -> lower(s).startsWith("set"))
        .noneMatch(s -> lower(s).contains("pg_stat_statements"))
        .noneMatch(s -> lower(s).contains("hypopg"))
        .noneMatch(s -> lower(s).contains("pglens:introspection"))
        .noneMatch(s -> lower(s).contains("pg_class")); // the marked catalog probe is excluded
  }

  @Test
  void limitCapsTheNumberOfRows() {
    assertThat(reader.topStatements(RankBy.CALLS, 1, 1)).hasSize(1);
  }

  @Test
  void globalStatsResetReflectsTheLastReset() {
    // seedWorkload() called pg_stat_statements_reset() in @BeforeAll, so the info view's single
    // stats_reset timestamp is present and recent. Asserted against a wide (1-hour) window, never a
    // wall-clock timing, so it is deterministic in CI. This is the PG16 reset signal (ADR-0024).
    Optional<Instant> reset = reader.globalStatsReset();
    assertThat(reset).isPresent();
    assertThat(reset.get())
        .isBeforeOrEqualTo(Instant.now())
        .isAfter(Instant.now().minusSeconds(3600));
  }

  private static String lower(StatementStat s) {
    return s.query().toLowerCase(Locale.ROOT);
  }

  private static Optional<StatementStat> find(List<StatementStat> rows, String needle) {
    return rows.stream().filter(s -> lower(s).contains(needle)).findFirst();
  }

  private static int indexOf(List<StatementStat> rows, String needle) {
    for (int i = 0; i < rows.size(); i++) {
      if (lower(rows.get(i)).contains(needle)) {
        return i;
      }
    }
    return -1;
  }
}
