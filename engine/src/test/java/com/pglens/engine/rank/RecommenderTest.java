package com.pglens.engine.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pglens.engine.model.AccessMethod;
import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.RankedRecommendation;
import com.pglens.engine.model.Recommendation;
import com.pglens.engine.model.ScoreBasis;
import com.pglens.engine.model.ValidationResult;
import com.pglens.engine.model.ValidationResult.Status;
import com.pglens.engine.model.ValueRangeEstimate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure, offline tests for the cross-query ranker and its near-duplicate dedupe. */
class RecommenderTest {

  private final Recommender recommender = new Recommender();

  @Test
  void emptyInputYieldsEmpty() {
    assertThat(recommender.rank(List.of())).isEmpty();
    assertThat(recommender.rank(null)).isEmpty();
  }

  @Test
  void ignoresNonValidatedRecommendations() {
    List<RankedRecommendation> ranked =
        recommender.rank(
            List.of(
                w(1, 1000, suppressed(btree("orders", "status"))),
                w(2, 1000, notValidated(gin("events", "payload")))));

    assertThat(ranked).isEmpty();
  }

  @Test
  void weightsRealTotalTimeNotJustRelativeDrop() {
    // A big estimated win on a cold query (0.9 × 10 = 9) must rank below a modest win on a hot one
    // (0.3 × 100 = 30) — the honest weighting the whole design turns on.
    Recommendation bigWinColdQuery = validated(btree("small", "a"), 0.90);
    Recommendation modestWinHotQuery = validated(btree("hot", "b"), 0.30);

    List<RankedRecommendation> ranked =
        recommender.rank(List.of(w(1, 10, bigWinColdQuery), w(2, 100, modestWinHotQuery)));

    assertThat(ranked).extracting(r -> r.candidate().table()).containsExactly("hot", "small");
    assertThat(ranked.get(0).estimatedMsSaved()).isEqualTo(30.0);
    assertThat(ranked.get(1).estimatedMsSaved()).isEqualTo(9.0);
    assertThat(ranked).allMatch(RankedRecommendation::actionable);
  }

  @Test
  void subsumesPrefixIndexUnderTheCompositeThatExtendsIt() {
    // One query wants orders(customer_id); another wants the composite orders(customer_id,
    // created_at). The composite serves both — the single-column one is redundant.
    Recommendation single = validated(btree("orders", "customer_id"), 0.50);
    Recommendation composite = validated(btree("orders", "customer_id", "created_at"), 0.40);

    List<RankedRecommendation> ranked =
        recommender.rank(List.of(w(1, 1000, single), w(2, 1000, composite)));

    RankedRecommendation singleRow = row(ranked, "idx_orders_customer_id");
    RankedRecommendation compositeRow = row(ranked, "idx_orders_customer_id_created_at");
    assertThat(compositeRow.actionable()).isTrue();
    assertThat(singleRow.subsumed()).isTrue();
    assertThat(singleRow.subsumedBy()).isEqualTo("idx_orders_customer_id_created_at");
  }

  @Test
  void subsumesExactDuplicateKeepingTheHigherScoringInstance() {
    // Two queries independently recommend the same index; keep it once (for the hotter query).
    Recommendation forHot = validated(btree("orders", "customer_id"), 0.50);
    Recommendation forCold = validated(btree("orders", "customer_id"), 0.50);

    List<RankedRecommendation> ranked =
        recommender.rank(List.of(w(1, 100, forCold), w(2, 900, forHot)));

    assertThat(ranked).hasSize(2);
    RankedRecommendation kept = ranked.get(0); // higher score first
    RankedRecommendation dup = ranked.get(1);
    assertThat(kept.queryId()).isEqualTo(2);
    assertThat(kept.actionable()).isTrue();
    assertThat(dup.queryId()).isEqualTo(1);
    assertThat(dup.subsumed()).isTrue();
    assertThat(dup.subsumedBy()).isEqualTo("idx_orders_customer_id");
  }

  @Test
  void doesNotSubsumeDifferentColumnsOrDifferentAccessMethod() {
    Recommendation onCustomer = validated(btree("orders", "customer_id"), 0.50);
    Recommendation onStatus = validated(btree("orders", "status"), 0.40);
    Recommendation hashOnCustomer =
        validated(
            IndexCandidate.of(
                "orders", List.of("customer_id"), AccessMethod.HASH, List.of("R1"), "hash"),
            0.30);

    List<RankedRecommendation> ranked =
        recommender.rank(
            List.of(w(1, 1000, onCustomer), w(2, 1000, onStatus), w(3, 1000, hashOnCustomer)));

    // Distinct columns never subsume; a hash on the same column is a different access method.
    assertThat(ranked).allMatch(RankedRecommendation::actionable);
    assertThat(ranked).hasSize(3);
  }

  // --- fixtures ---------------------------------------------------------------

  @Test
  void ranksByTheValueRangeFloorWhenOneExists() {
    // Same query weight; the generic plan says 99 % for both, but one index's worst common value
    // gains only 20 % — it must rank below the index whose floor is 60 % (ADR-0038).
    Recommendation hotKey = withRange(validated(btree("orders", "customer_id"), 0.99), 0.20, 0.018);
    Recommendation evenKey =
        withRange(validated(btree("events", "customer_id"), 0.99), 0.60, 0.001);

    List<RankedRecommendation> ranked =
        recommender.rank(List.of(w(1, 1000, hotKey), w(2, 1000, evenKey)));

    assertThat(ranked.get(0).candidate().table()).isEqualTo("events");
    assertThat(ranked.get(0).estimatedMsSaved()).isEqualTo(600.0);
    assertThat(ranked.get(0).scoreBasis()).isEqualTo(ScoreBasis.VALUE_RANGE_FLOOR);
    assertThat(ranked.get(1).estimatedMsSaved()).isCloseTo(200.0, within(1e-9));
  }

  @Test
  void usesTheGenericFigureWithoutARange() {
    List<RankedRecommendation> ranked =
        recommender.rank(List.of(w(1, 100, validated(btree("t", "a"), 0.5))));
    assertThat(ranked.get(0).scoreBasis()).isEqualTo(ScoreBasis.GENERIC_PLAN);
    assertThat(ranked.get(0).estimatedMsSaved()).isEqualTo(50.0);
  }

  @Test
  void aSubsumedRecStaysUnresolvedUntilItsCoverageIsChecked() {
    List<RankedRecommendation> ranked =
        recommender.rank(
            List.of(
                w(1, 100, validated(btree("orders", "customer_id"), 0.9)),
                w(2, 500, validated(btree("orders", "customer_id", "created_at"), 0.9))));
    RankedRecommendation prefix = row(ranked, "idx_orders_customer_id");

    assertThat(prefix.subsumedByDdl())
        .isEqualTo(
            "CREATE INDEX idx_orders_customer_id_created_at ON orders (customer_id, created_at);");
    assertThat(prefix.coverage()).isNull();
    assertThat(prefix.actionable()).isFalse();

    // The general index fails the prefix query's gate → the prefix index is still needed.
    RankedRecommendation failed = prefix.withCoverage(suppressed(btree("x", "y")).validation());
    assertThat(failed.actionable()).isTrue();
    assertThat(failed.coveredBySubsumer()).isFalse();
    // …and passes it → redundant.
    RankedRecommendation passed = prefix.withCoverage(validated(btree("x", "y"), 0.8).validation());
    assertThat(passed.actionable()).isFalse();
    assertThat(passed.coveredBySubsumer()).isTrue();
  }

  private static Recommendation withRange(Recommendation r, double worst, double frequency) {
    ValueRangeEstimate range =
        new ValueRangeEstimate("t.c", 4, worst, frequency, 0.99, "range label");
    return new Recommendation(r.candidate(), r.validation().withEvidence(range, null));
  }

  private static IndexCandidate btree(String table, String... columns) {
    return IndexCandidate.of(table, List.of(columns), AccessMethod.BTREE, List.of("R1"), "test");
  }

  private static IndexCandidate gin(String table, String column) {
    return IndexCandidate.of(table, List.of(column), AccessMethod.GIN, List.of("R7"), "jsonb");
  }

  private static Recommendation validated(IndexCandidate candidate, double relativeDelta) {
    ValidationResult v =
        new ValidationResult(
            Status.PLANNER_VALIDATED,
            100.0,
            100.0 * (1 - relativeDelta),
            relativeDelta,
            true,
            "HypoPG planner Estimate");
    return new Recommendation(candidate, v);
  }

  private static Recommendation suppressed(IndexCandidate candidate) {
    return new Recommendation(
        candidate, new ValidationResult(Status.SUPPRESSED, 100.0, 100.0, 0.0, false, "not used"));
  }

  private static Recommendation notValidated(IndexCandidate candidate) {
    return new Recommendation(
        candidate,
        new ValidationResult(Status.NOT_PLANNER_VALIDATED, null, null, null, false, "GIN"));
  }

  private static Recommender.Weighted w(long queryId, double totalMs, Recommendation rec) {
    return new Recommender.Weighted(queryId, totalMs, rec);
  }

  private static RankedRecommendation row(List<RankedRecommendation> ranked, String indexName) {
    return ranked.stream()
        .filter(r -> r.candidate().suggestedName().equals(indexName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no ranked row for " + indexName));
  }
}
