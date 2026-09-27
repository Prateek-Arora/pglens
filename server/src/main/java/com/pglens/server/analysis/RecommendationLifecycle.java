package com.pglens.server.analysis;

import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.IndexInfo;
import com.pglens.engine.model.TableInfo;
import com.pglens.engine.rank.CoverageChecks.Verdict;
import com.pglens.server.persistence.AnalysisRepository.StoredRecommendation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * What happens to each stored recommendation after an analysis pass (ADR-0051). Pure.
 *
 * <ul>
 *   <li><b>Still proposed</b> by the engine → kept (and un-marked if it had been applied: the index
 *       was dropped and the advice is back).
 *   <li><b>No longer proposed, and an index that serves it appeared after it was recommended</b> →
 *       <em>applied</em>: kept, with the index and when PgLens first saw it, so the measured time
 *       before and after can be compared.
 *   <li><b>No longer proposed otherwise</b> (the plan changed, the engine changed, or an index that
 *       predates the advice serves it) → deleted, so the dashboard never shows stale advice.
 * </ul>
 *
 * A query whose stored plan didn't parse this pass keeps its recommendations: not seeing its
 * candidates is not evidence that they are gone.
 */
final class RecommendationLifecycle {

  private RecommendationLifecycle() {}

  /** {@code index} serves {@code rec}, first seen at {@code at}. */
  record Applied(StoredRecommendation rec, String index, Instant at) {}

  record Changes(
      List<StoredRecommendation> delete,
      List<Applied> markApplied,
      List<StoredRecommendation> unapply) {
    boolean isEmpty() {
      return delete.isEmpty() && markApplied.isEmpty() && unapply.isEmpty();
    }
  }

  /**
   * @param proposed every (query, index) pair the engine proposed this pass, coverage checks
   *     included
   * @param unreadable queries whose plan couldn't be analysed this pass
   * @param firstSeen when PgLens first saw an existing index, by name, after an instant (or ever,
   *     for null): a recommendation that became advice again only counts the index's new life
   */
  static Changes changes(
      List<StoredRecommendation> stored,
      Set<Verdict> proposed,
      Set<Long> unreadable,
      CatalogSnapshot catalog,
      BiFunction<String, @Nullable Instant, Optional<Instant>> firstSeen) {
    List<StoredRecommendation> delete = new ArrayList<>();
    List<Applied> markApplied = new ArrayList<>();
    List<StoredRecommendation> unapply = new ArrayList<>();
    for (StoredRecommendation rec : stored) {
      if (proposed.contains(rec.key())) {
        if (rec.appliedIndex() != null) {
          unapply.add(rec);
        }
        continue;
      }
      if (rec.appliedIndex() != null || unreadable.contains(rec.queryid())) {
        continue;
      }
      Optional<Applied> applied =
          servingIndex(rec, catalog)
              .flatMap(
                  ix ->
                      firstSeen
                          .apply(ix.name(), rec.activeSince())
                          .filter(at -> at.isAfter(rec.createdAt()))
                          .map(at -> new Applied(rec, ix.name(), at)));
      if (applied.isPresent()) {
        markApplied.add(applied.get());
      } else {
        delete.add(rec);
      }
    }
    return new Changes(delete, markApplied, unapply);
  }

  private static Optional<IndexInfo> servingIndex(
      StoredRecommendation rec, CatalogSnapshot catalog) {
    Optional<IndexCandidate> candidate = IndexCandidate.parseDdl(rec.ddl());
    if (candidate.isEmpty()) {
      return Optional.empty();
    }
    return catalog
        .table(candidate.get().table())
        .map(TableInfo::indexes)
        .flatMap(indexes -> indexes.stream().filter(candidate.get()::servedBy).findFirst());
  }
}
