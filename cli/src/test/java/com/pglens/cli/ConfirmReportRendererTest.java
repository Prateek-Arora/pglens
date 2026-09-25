package com.pglens.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.confirm.ConfirmReport;
import com.pglens.engine.confirm.IndexConfirmation;
import com.pglens.engine.confirm.QueryConfirmation;
import com.pglens.engine.confirm.Verdict;
import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.TargetInfo;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The confirm report, human and JSON (Phase 2.6, ADR-0042). */
class ConfirmReportRendererTest {

  private static QueryConfirmation q(
      long id, double est, Verdict v, double drop, double before, double after) {
    return new QueryConfirmation(id, est, v, drop, before, after, false, 1, 0, null);
  }

  private static ConfirmReport report(boolean dryRun, List<IndexConfirmation> indexes) {
    return new ConfirmReport(
        ConfirmReport.SCHEMA_VERSION,
        "2026-09-25T12:00:00Z",
        dryRun,
        "2026-09-25T10:00:00Z",
        new TargetInfo("db.internal", 5432, "imdb", "16.4", List.of()),
        new TargetInfo("localhost", 5433, "imdb_copy", "16.4", List.of()),
        new ConfirmReport.Settings(10, 5, 3, 300_000, 0.15, -0.05),
        new ConfirmReport.StatementCounts(113, 2, 0, 1, 113, 90, 180),
        indexes,
        List.of("Times were measured on the copy."));
  }

  private static IndexConfirmation castInfo() {
    return new IndexConfirmation(
        3,
        "CREATE INDEX ON cast_info (movie_id);",
        "cast_info",
        List.of("movie_id"),
        AccessMethod.BTREE,
        Verdict.FASTER,
        0.25,
        1000.0,
        750.0,
        false,
        41_000L,
        380L << 20,
        null,
        null,
        List.of(
            q(101, 0.95, Verdict.FASTER, 0.9, 900, 90),
            q(102, 0.9075, Verdict.SLOWER, -6.8162, 930.98, 7276.7)));
  }

  @Test
  void showsTheMeasuredVerdictBesideTheEstimateAndFlagsSlowerQueries() {
    IndexConfirmation unbuildable =
        new IndexConfirmation(
            2,
            "CREATE INDEX ON movie_info (info);",
            "movie_info",
            List.of("info"),
            AccessMethod.BTREE,
            Verdict.UNBUILDABLE,
            null,
            null,
            null,
            false,
            null,
            null,
            "index row requires 9392 bytes, maximum size is 8191",
            "Build caution: movie_info stores …",
            List.of(new QueryConfirmation(201, 0.68, null, null, null, null, false, 2, 0, null)));

    String out = ConfirmReportRenderer.toHuman(report(false, List.of(unbuildable, castInfo())));

    assertThat(out)
        .contains(
            "PgLens confirm — measured on the copy imdb_copy @ localhost:5433  (PostgreSQL 16.4)")
        .contains("Checks the scan of imdb @ db.internal:5432 from 2026-09-25T10:00:00Z")
        .contains("113 read-only statements (2 others skipped) · 113 query shapes · 90 matched")
        .contains("#2  CREATE INDEX ON movie_info (info);")
        .contains("COULDN'T BE BUILT  index row requires 9392 bytes, maximum size is 8191")
        .contains("⚠ Build caution: movie_info stores …")
        .contains("#3  CREATE INDEX ON cast_info (movie_id);")
        .contains("FASTER  −25.0% measured (1,000.0 → 750.0 ms warm time over its statements)")
        .contains("built in 41,000 ms · 380.0 MB")
        .contains("⚠ faster overall, but slower for 1 of its 2 queries (below)")
        .contains("queryid 102   estimate −90.8%   measured +681.6%   SLOWER")
        .contains(
            "Summary: 1 faster · 0 no real effect · 0 slower · 1 couldn't be built · 0 not measured")
        .contains("measured on the copy, not production");
  }

  @Test
  void aDryRunListsWhatWouldBeMeasured() {
    IndexConfirmation planned =
        new IndexConfirmation(
            1,
            "CREATE INDEX ON t (a);",
            "t",
            List.of("a"),
            AccessMethod.BTREE,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            List.of(
                new QueryConfirmation(7, 0.5, null, null, null, null, false, 3, 0, null),
                new QueryConfirmation(8, 0.4, null, null, null, null, false, 0, 0, null)));
    String out = ConfirmReportRenderer.toHuman(report(true, List.of(planned)));
    assertThat(out)
        .contains("Dry run — nothing was built")
        .contains("queryid 7   estimate −50.0%   3 statements")
        .contains("queryid 8   estimate −40.0%   0 statements (none in the workload)")
        .doesNotContain("Summary:");
  }

  @Test
  void theJsonIsTheVersionedContract() {
    String json = ConfirmReportRenderer.toJson(report(false, List.of(castInfo())));
    assertThat(json)
        .contains("\"schemaVersion\" : \"1.0\"")
        .contains("\"verdict\" : \"FASTER\"")
        .contains("\"slowerQueries\" : 1")
        .contains("\"fasterQueries\" : 1")
        .contains("\"port\" : 5433")
        .doesNotContain("\"reason\""); // nulls are omitted
  }
}
