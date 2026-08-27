package com.pglens.engine.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.model.ConnectionTarget;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Capture-robustness harness for the fragile surface of the pipeline: plan capture. A statement
 * whose {@code pg_stat_statements}-normalized text cannot be {@code EXPLAIN (GENERIC_PLAN)}'d is
 * dropped <b>silently</b> ({@code planCaptured=false}, no finding, no error) — the exact class of
 * bug ADR-0021 fixed, where a typed literal ({@code interval '7 days'} → {@code interval $1}) was a
 * syntax error to EXPLAIN and a whole class of temporal-range queries vanished with no signal.
 *
 * <p>This test replays {@code robustness/shape_stressors.sql} — diverse but valid SELECT shapes the
 * neat demo does not cover — so the real {@code pg_stat_statements} normalizer produces their
 * normalized text, reads that text back, and asserts the capturer plans (almost) all of them. The
 * skip-rate is the honesty metric: a new shape that silently skips fails CI, and any deliberate
 * skip must be enumerated in {@link #EXPECTED_SKIP_SUBSTRINGS} with a reason.
 *
 * <p>Assertions are on capture-succeeded / skipped only — no wall-clock — so they are CI-stable.
 */
@Tag("it")
@Testcontainers
class PlanCaptureRobustnessTest {

  @Container static final PostgreSQLContainer<?> DB = MonitoredDbContainer.create();

  /**
   * Normalized-text fragments of shapes we knowingly accept as un-capturable. Add an entry only
   * with a documented reason — never to silence a real regression.
   *
   * <ul>
   *   <li>{@code = ANY (ARRAY[$1, $2, ...])} on a non-text column — pg_stat_statements keeps the
   *       explicit array constructor and types the normalized {@code $N} as {@code text}, so
   *       GENERIC_PLAN sees {@code integer = ANY(text[])} and errors "operator does not exist". A
   *       safe fix needs catalog-type-aware rewriting (backlog: type-aware capture). The commoner
   *       forms — an IN-list ({@code col IN ($1,...)}) and a single array param ({@code col = ANY
   *       ($1)}) — both capture. The skip is <b>surfaced</b>, not silent: the query still ranks
   *       with {@code planCaptured=false} and the renderer labels it "plan not captured … skipped".
   * </ul>
   */
  private static final Set<String> EXPECTED_SKIP_SUBSTRINGS = Set.of("= ANY (ARRAY[");

  private static PlanCapturer capturer;
  private static List<String> normalizedShapes;

  @BeforeAll
  static void warmAndCollectNormalizedShapes() throws IOException {
    MonitoredDbContainer.initSchema(DB);
    ConnectionTarget target =
        new ConnectionTarget(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), "pglens_demo");
    JdbcTemplate jdbc =
        new JdbcTemplate(DataSources.forScan(target)); // read-only by nature; no guards
    capturer = new PlanCapturer(jdbc);

    // Forget schema DDL, then execute each stressor so pg_stat_statements normalizes it. Empty
    // tables are fine — GENERIC_PLAN plans the normalized text without needing data.
    jdbc.execute("SELECT pg_stat_statements_reset()");
    for (String statement : loadStressors()) {
      jdbc.execute(statement);
    }

    // Read the normalized text back, mirroring StatsReader hygiene (top-level, this DB) minus the
    // utility filter — every stressor is a SELECT we want to keep. The read-back and the reset call
    // are the only rows mentioning pg_stat_statements, so that one predicate excludes our own
    // noise.
    normalizedShapes =
        jdbc.queryForList(
            "SELECT query FROM pg_stat_statements "
                + "WHERE toplevel "
                + "AND dbid = (SELECT oid FROM pg_database WHERE datname = current_database()) "
                + "AND query NOT ILIKE '%pg_stat_statements%'",
            String.class);
  }

  @Test
  void everyRealisticQueryShapeIsCapturedWithoutSilentSkips() {
    assertThat(normalizedShapes)
        .as("pg_stat_statements should have recorded the stressor shapes")
        .hasSizeGreaterThanOrEqualTo(20);

    List<String> skipped = new ArrayList<>();
    for (String normalized : normalizedShapes) {
      if (capturer.captureGenericPlanJson(normalized).isEmpty()) {
        skipped.add(normalized);
      }
    }

    List<String> unexpected =
        skipped.stream()
            .filter(s -> EXPECTED_SKIP_SUBSTRINGS.stream().noneMatch(s::contains))
            .toList();

    double skipRate = (double) skipped.size() / normalizedShapes.size();
    assertThat(unexpected)
        .as(
            "%d/%d shapes skipped (%.1f%%) — a silently un-capturable shape is the ADR-0021 class of"
                + " bug; fix the capturer or list it in EXPECTED_SKIP_SUBSTRINGS with a reason.%n%s",
            skipped.size(), normalizedShapes.size(), skipRate * 100, String.join("\n", unexpected))
        .isEmpty();
  }

  private static List<String> loadStressors() throws IOException {
    try (InputStream in =
        PlanCaptureRobustnessTest.class.getResourceAsStream("/robustness/shape_stressors.sql")) {
      if (in == null) {
        throw new IllegalStateException("robustness/shape_stressors.sql not on the test classpath");
      }
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      String withoutComments =
          sql.lines()
              .filter(line -> !line.stripLeading().startsWith("--"))
              .collect(Collectors.joining("\n"));
      return Arrays.stream(withoutComments.split(";"))
          .map(String::strip)
          .filter(s -> !s.isEmpty())
          .toList();
    }
  }
}
