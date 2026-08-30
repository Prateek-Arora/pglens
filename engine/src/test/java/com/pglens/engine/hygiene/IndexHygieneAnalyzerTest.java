package com.pglens.engine.hygiene;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.hygiene.IndexHygieneFinding.Kind;
import com.pglens.engine.model.IndexInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the index-hygiene rule — no DB. They pin the three finding kinds, the
 * priority, and above all the B5 safety invariant: a unique / PK / FK / constraint-backing index is
 * <b>never</b> flagged, no matter how duplicated or unused it looks.
 */
class IndexHygieneAnalyzerTest {

  private final IndexHygieneAnalyzer analyzer = new IndexHygieneAnalyzer();

  private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-08-08T00:00:00Z");

  private static IndexInfo plain(String name, String... columns) {
    return new IndexInfo(
        "orders", name, List.of(columns), false, false, "btree", null, "def " + name, false, 0L);
  }

  private static IndexInfo guarded(String name, String... columns) {
    // unique => guarded (also stands in for PK / FK / constraint-backed for the invariant tests).
    return new IndexInfo(
        "orders", name, List.of(columns), true, false, "btree", null, "def " + name, true, 0L);
  }

  private static Map<String, IndexScanWindow> unused(String... names) {
    return java.util.Arrays.stream(names)
        .collect(
            java.util.stream.Collectors.toMap(n -> n, n -> new IndexScanWindow(0L, 6, FROM, TO)));
  }

  @Test
  void flagsExactlyOneOfADuplicatePairAsDuplicate() {
    List<IndexHygieneFinding> findings =
        analyzer.analyze(
            List.of(plain("idx_a", "customer_id"), plain("idx_b", "customer_id")), Map.of());

    assertThat(findings).hasSize(1);
    IndexHygieneFinding f = findings.get(0);
    assertThat(f.kind()).isEqualTo(Kind.DUPLICATE);
    assertThat(f.indexName()).isEqualTo("idx_b"); // survivor is the lower name, idx_a
    assertThat(f.relatedIndex()).isEqualTo("idx_a");
    assertThat(f.reason()).contains("Duplicate of idx_a");
  }

  @Test
  void whenADuplicateShadowsAUniqueIndexThePlainOneYieldsAndTheUniqueSurvives() {
    List<IndexHygieneFinding> findings =
        analyzer.analyze(
            List.of(
                guarded("orders_customer_uniq", "customer_id"), plain("idx_dup", "customer_id")),
            Map.of());

    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).indexName()).isEqualTo("idx_dup"); // never the unique one
    assertThat(findings.get(0).kind()).isEqualTo(Kind.DUPLICATE);
    assertThat(findings.get(0).relatedIndex()).isEqualTo("orders_customer_uniq");
  }

  @Test
  void flagsAPrefixIndexAsRedundantOfTheWiderOne() {
    List<IndexHygieneFinding> findings =
        analyzer.analyze(
            List.of(
                plain("idx_status", "status"), plain("idx_status_created", "status", "created_at")),
            Map.of());

    assertThat(findings).hasSize(1);
    IndexHygieneFinding f = findings.get(0);
    assertThat(f.kind()).isEqualTo(Kind.REDUNDANT);
    assertThat(f.indexName()).isEqualTo("idx_status");
    assertThat(f.relatedIndex()).isEqualTo("idx_status_created");
  }

  @Test
  void flagsAnIndexWithNoScansOverTheWindowAsUnused() {
    List<IndexHygieneFinding> findings =
        analyzer.analyze(List.of(plain("idx_lonely", "total_cents")), unused("idx_lonely"));

    assertThat(findings).hasSize(1);
    IndexHygieneFinding f = findings.get(0);
    assertThat(f.kind()).isEqualTo(Kind.UNUSED);
    assertThat(f.relatedIndex()).isNull();
    assertThat(f.reason()).contains("No index scans").contains("6 snapshots");
  }

  @Test
  void doesNotFlagAnIndexThatWasScannedOrHasTooLittleHistory() {
    Map<String, IndexScanWindow> scanned = Map.of("idx_hot", new IndexScanWindow(42L, 6, FROM, TO));

    // idx_hot grew (not unused); idx_new is absent from the map (< 2 snapshots) → never judged.
    List<IndexHygieneFinding> findings =
        analyzer.analyze(List.of(plain("idx_hot", "a"), plain("idx_new", "b")), scanned);

    assertThat(findings).isEmpty();
  }

  @Test
  void neverFlagsAGuardedIndexEvenWhenItLooksUnused() {
    // A unique/PK/FK/constraint index with a zero-scan window must still never be flagged.
    List<IndexHygieneFinding> findings =
        analyzer.analyze(List.of(guarded("orders_pkey", "id")), unused("orders_pkey"));

    assertThat(findings).isEmpty();
  }

  @Test
  void duplicateTakesPriorityOverUnusedForTheSameIndex() {
    // idx_b is both a duplicate of idx_a AND unused — the stronger, always-safe DUPLICATE wins.
    List<IndexHygieneFinding> findings =
        analyzer.analyze(
            List.of(plain("idx_a", "customer_id"), plain("idx_b", "customer_id")), unused("idx_b"));

    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).indexName()).isEqualTo("idx_b");
    assertThat(findings.get(0).kind()).isEqualTo(Kind.DUPLICATE);
  }

  @Test
  void aDifferentAccessMethodOrPredicateIsNotADuplicate() {
    IndexInfo btree = plain("idx_btree", "customer_id");
    IndexInfo hash =
        new IndexInfo(
            "orders",
            "idx_hash",
            List.of("customer_id"),
            false,
            false,
            "hash",
            null,
            "def",
            false,
            0L);
    IndexInfo partial =
        new IndexInfo(
            "orders",
            "idx_partial",
            List.of("customer_id"),
            false,
            false,
            "btree",
            "status = 'open'",
            "def",
            false,
            0L);

    // None are duplicates/redundant of each other (method or predicate differs); none unused.
    assertThat(analyzer.analyze(List.of(btree, hash, partial), Map.of())).isEmpty();
  }
}
