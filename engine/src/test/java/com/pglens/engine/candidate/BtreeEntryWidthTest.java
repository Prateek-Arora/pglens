package com.pglens.engine.candidate;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.candidate.BtreeEntryWidth.KeyColumn;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure tests for the B-tree build caution (backlog B17, ADR-0041). */
class BtreeEntryWidthTest {

  private static KeyColumn col(String name, String type, int typmod) {
    return new KeyColumn(name, type, 'S', typmod, 'x');
  }

  @Test
  void anUnboundedColumnOnATableWithToastDataGetsACautionAndACheckQuery() {
    // JOB/IMDB: movie_info (info) is text, and movie_info keeps long values out of line.
    String caution =
        BtreeEntryWidth.caution("movie_info", List.of(col("info", "text", -1)), 120L << 20)
            .orElseThrow();

    assertThat(caution)
        .startsWith("Build caution: movie_info stores 120.0 MB of values too long to keep in line")
        .contains("at most ~2.7 kB", "any value of info is longer", "HypoPG can't check this")
        .endsWith("Check first: SELECT max(pg_column_size(info)) FROM movie_info;");
  }

  @Test
  void noToastDataMeansNoValueIsTooWide() {
    // Most text columns (emails, names, statuses) never reach TOAST — no noise for them.
    assertThat(BtreeEntryWidth.caution("customers", List.of(col("email", "text", -1)), 0L))
        .isEmpty();
  }

  @Test
  void storageMainKeepsLongValuesInLineSoItStillWarnsWithoutToast() {
    KeyColumn inline = new KeyColumn("doc", "text", 'S', -1, 'm');
    assertThat(BtreeEntryWidth.caution("docs", List.of(inline), 0L))
        .hasValueSatisfying(c -> assertThat(c).contains("keeps long values in line"));
  }

  @Test
  void boundedColumnsNeverWarnEvenOnAToastedTable() {
    assertThat(
            BtreeEntryWidth.caution(
                "cast_info",
                List.of(col("movie_id", "int4", -1), col("code", "varchar", 64 + 4)),
                1L << 30))
        .isEmpty();
  }

  @Test
  void onlyTheRiskyColumnsAreNamedInAMultiColumnIndex() {
    String caution =
        BtreeEntryWidth.caution(
                "events", List.of(col("customer_id", "int4", -1), col("note", "text", -1)), 8192L)
            .orElseThrow();
    assertThat(caution)
        .contains("any value of note is longer", "SELECT max(pg_column_size(note)) FROM events;")
        .doesNotContain("customer_id");
  }

  @Test
  void classifiesDeclaredTypes() {
    assertThat(BtreeEntryWidth.unbounded(col("a", "text", -1))).isTrue();
    assertThat(BtreeEntryWidth.unbounded(col("a", "bytea", -1))).isTrue();
    assertThat(BtreeEntryWidth.unbounded(col("a", "jsonb", -1))).isTrue();
    assertThat(BtreeEntryWidth.unbounded(col("a", "varchar", -1))).isTrue(); // no length limit
    assertThat(BtreeEntryWidth.unbounded(col("a", "varchar", 255 + 4))).isFalse(); // ≤ 1,020 B
    assertThat(BtreeEntryWidth.unbounded(col("a", "varchar", 1000 + 4))).isTrue(); // up to 4 kB
    assertThat(BtreeEntryWidth.unbounded(col("a", "int8", -1))).isFalse();
    assertThat(BtreeEntryWidth.unbounded(new KeyColumn("a", "_int4", 'A', -1, 'x'))).isTrue();
  }
}
