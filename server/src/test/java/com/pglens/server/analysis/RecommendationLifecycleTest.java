package com.pglens.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.IndexInfo;
import com.pglens.engine.model.TableInfo;
import com.pglens.engine.rank.CoverageChecks.Verdict;
import com.pglens.server.analysis.RecommendationLifecycle.Changes;
import com.pglens.server.persistence.AnalysisRepository.StoredRecommendation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecommendationLifecycleTest {

  private static final String DDL = "CREATE INDEX idx_orders_customer_id ON orders (customer_id);";
  private static final Instant RECOMMENDED = Instant.parse("2026-09-27T08:07:00Z");
  private static final Instant BUILT = Instant.parse("2026-09-27T08:08:45Z");
  private static final StoredRecommendation REC =
      new StoredRecommendation(42L, DDL, RECOMMENDED, null, null);

  @Test
  void aStillProposedRecommendationIsKept() {
    Changes c = changes(List.of(REC), Set.of(new Verdict(42L, DDL)), catalog());
    assertThat(c.isEmpty()).isTrue();
  }

  @Test
  void anIndexBuiltAfterTheAdviceMarksItApplied() {
    Changes c = changes(List.of(REC), Set.of(), catalog(index("my_customer_idx", "customer_id")));

    assertThat(c.delete()).isEmpty();
    assertThat(c.markApplied())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.rec()).isEqualTo(REC);
              assertThat(a.index()).isEqualTo("my_customer_idx");
              assertThat(a.at()).isEqualTo(BUILT);
            });
  }

  @Test
  void adviceNoLongerProposedWithNoServingIndexIsDeleted() {
    Changes c = changes(List.of(REC), Set.of(), catalog(index("orders_created_idx", "created_at")));
    assertThat(c.delete()).containsExactly(REC);
    assertThat(c.markApplied()).isEmpty();
  }

  @Test
  void anIndexThatPredatesTheAdviceIsNotCreditedToIt() {
    StoredRecommendation later =
        new StoredRecommendation(42L, DDL, BUILT.plusSeconds(60), null, null);
    Changes c = changes(List.of(later), Set.of(), catalog(index("my_customer_idx", "customer_id")));
    assertThat(c.delete()).containsExactly(later);
    assertThat(c.markApplied()).isEmpty();
  }

  @Test
  void oldFormatAndSystemCatalogAdviceIsDeleted() {
    StoredRecommendation catalogRec =
        new StoredRecommendation(
            7L,
            "CREATE INDEX idx_pg_authid_tableoid ON pg_authid (tableoid);",
            RECOMMENDED,
            null,
            null);
    StoredRecommendation garbage =
        new StoredRecommendation(8L, "not ddl at all", RECOMMENDED, null, null);
    Changes c = changes(List.of(catalogRec, garbage), Set.of(), catalog());
    assertThat(c.delete()).containsExactly(catalogRec, garbage);
  }

  @Test
  void anAppliedRecommendationStaysAppliedUntilProposedAgain() {
    StoredRecommendation applied =
        new StoredRecommendation(42L, DDL, RECOMMENDED, "my_customer_idx", null);
    assertThat(changes(List.of(applied), Set.of(), catalog()).isEmpty())
        .as("kept as history even after the index is gone, until the engine proposes it again")
        .isTrue();

    Changes back = changes(List.of(applied), Set.of(new Verdict(42L, DDL)), catalog());
    assertThat(back.unapply()).containsExactly(applied);
  }

  @Test
  void aQueryWhosePlanDidNotParseKeepsItsAdvice() {
    Changes c =
        RecommendationLifecycle.changes(
            List.of(REC), Set.of(), Set.of(42L), catalog(), (name, since) -> Optional.empty());
    assertThat(c.isEmpty()).isTrue();
  }

  @Test
  void aRebuiltIndexIsDatedFromItsNewLifeNotItsOldName() {
    Instant readvised = BUILT.plusSeconds(3600);
    Instant rebuilt = readvised.plusSeconds(120);
    StoredRecommendation rec = new StoredRecommendation(42L, DDL, RECOMMENDED, null, readvised);
    Changes c =
        RecommendationLifecycle.changes(
            List.of(rec),
            Set.of(),
            Set.of(),
            catalog(index("idx_orders_customer_id", "customer_id")),
            (name, since) -> Optional.of(since == null || BUILT.isAfter(since) ? BUILT : rebuilt));
    assertThat(c.markApplied()).singleElement().extracting(a -> a.at()).isEqualTo(rebuilt);
  }

  private static Changes changes(
      List<StoredRecommendation> stored, Set<Verdict> proposed, CatalogSnapshot c) {
    return RecommendationLifecycle.changes(
        stored, proposed, Set.of(), c, (name, since) -> Optional.of(BUILT));
  }

  private static CatalogSnapshot catalog(IndexInfo... indexes) {
    return new CatalogSnapshot(
        Map.of("orders", new TableInfo("orders", 400_000, List.of(indexes))));
  }

  private static IndexInfo index(String name, String column) {
    return new IndexInfo("orders", name, List.of(column), false, false, "btree", null);
  }
}
