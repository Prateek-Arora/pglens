package com.pglens.engine.rank;

import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.RankedRecommendation;
import com.pglens.engine.model.Recommendation;
import com.pglens.engine.model.ScoreBasis;
import com.pglens.engine.model.ValidationResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks the planner-validated recommendations across all queries and flags near-duplicates, so
 * PgLens never advises two overlapping indexes (ADR-0017). Pure — no I/O.
 *
 * <p><b>Score</b> = the query's real total exec time × a HypoPG relative cost drop — the
 * value-range worst case when one exists, else the generic plan's ({@link RankingScore}, ADR-0038)
 * — a measured weight scaled by an estimated fraction, so a modest win on a hot query outranks a
 * big win on a cold one. Only validated recs are ranked; suppressed/not-validated ones stay
 * per-query.
 *
 * <p><b>Dedupe:</b> a btree on {@code (a)} is served by a btree on {@code (a, b)}, so a rec whose
 * key columns are a prefix of another's (same table + access method) is flagged {@code subsumedBy}
 * the more general one, not dropped. Scores are never summed onto the survivor — each rec keeps its
 * own, HypoPG-validated against its own query (no fabricated aggregate).
 */
public final class Recommender {

  /** One recommendation to rank, tagged with its owning query's id and real total time (ms). */
  public record Weighted(
      long queryId, double queryTotalExecTimeMs, Recommendation recommendation) {}

  /**
   * Ranks the validated recommendations by estimated time saved (descending) and marks near-
   * duplicates. Non-validated inputs are ignored; empty in → empty out.
   */
  public List<RankedRecommendation> rank(List<Weighted> input) {
    if (input == null || input.isEmpty()) {
      return List.of();
    }

    List<Scored> scored = new ArrayList<>();
    for (Weighted w : input) {
      if (w.recommendation().isRecommended()) {
        ValidationResult v = w.recommendation().validation();
        Double worstCase = v.valueRange() == null ? null : v.valueRange().worstRelativeDrop();
        RankingScore.Drop drop = RankingScore.drop(v.relativeDelta(), worstCase);
        scored.add(new Scored(w, w.queryTotalExecTimeMs() * drop.value(), drop.basis()));
      }
    }
    if (scored.isEmpty()) {
      return List.of();
    }

    markSubsumed(scored);

    scored.sort(
        Comparator.comparingDouble((Scored s) -> s.score)
            .reversed()
            .thenComparing(s -> s.candidate().suggestedName()));

    List<RankedRecommendation> out = new ArrayList<>(scored.size());
    for (Scored s : scored) {
      out.add(
          new RankedRecommendation(
              s.weighted.queryId(),
              s.weighted.queryTotalExecTimeMs(),
              s.weighted.recommendation(),
              s.score,
              s.basis,
              s.subsumedBy != null,
              s.subsumedBy == null ? null : s.subsumedBy.candidate().suggestedName(),
              s.subsumedBy == null ? null : s.subsumedBy.candidate().ddl(),
              null));
    }
    return out;
  }

  /**
   * Flags each recommendation covered by a more general one. Processing most-general first (longest
   * key, higher score breaking ties) means a general index is always kept before the prefixes it
   * covers, and exact duplicates keep the higher-scoring instance.
   */
  private static void markSubsumed(List<Scored> scored) {
    List<Scored> byGenerality = new ArrayList<>(scored);
    byGenerality.sort(
        Comparator.comparingInt((Scored s) -> s.candidate().columns().size())
            .reversed()
            .thenComparing(Comparator.comparingDouble((Scored s) -> s.score).reversed())
            .thenComparing(s -> s.candidate().suggestedName()));

    List<Scored> kept = new ArrayList<>();
    for (Scored s : byGenerality) {
      Scored cover = findCover(kept, s.candidate());
      if (cover == null) {
        kept.add(s);
      } else {
        s.subsumedBy = cover;
      }
    }
  }

  private static Scored findCover(List<Scored> kept, IndexCandidate candidate) {
    for (Scored k : kept) {
      if (k.candidate().covers(candidate)) {
        return k;
      }
    }
    return null;
  }

  /** Mutable working row: a weighted rec, its score, and the index that subsumes it (if any). */
  private static final class Scored {
    final Weighted weighted;
    final double score;
    final ScoreBasis basis;
    Scored subsumedBy;

    Scored(Weighted weighted, double score, ScoreBasis basis) {
      this.weighted = weighted;
      this.score = score;
      this.basis = basis;
    }

    IndexCandidate candidate() {
      return weighted.recommendation().candidate();
    }
  }
}
