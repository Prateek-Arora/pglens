package com.pglens.engine.rank;

import com.pglens.engine.model.IndexCandidate;
import com.pglens.engine.model.RankedRecommendation;
import com.pglens.engine.model.Recommendation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks the planner-validated recommendations across all queries and flags near-duplicates, so
 * PgLens never advises two overlapping indexes (ADR-0017). Pure — no I/O.
 *
 * <p><b>Score</b> = the query's real total exec time × the HypoPG generic-plan relative cost drop —
 * a measured weight scaled by an estimated fraction, so a modest win on a hot query outranks a big
 * win on a cold one. Only validated recs are ranked; suppressed/not-validated ones stay per-query.
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
        double delta = orZero(w.recommendation().validation().relativeDelta());
        scored.add(new Scored(w, w.queryTotalExecTimeMs() * delta));
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
              s.subsumedBy != null,
              s.subsumedBy));
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
        s.subsumedBy = cover.candidate().suggestedName();
      }
    }
  }

  private static Scored findCover(List<Scored> kept, IndexCandidate candidate) {
    for (Scored k : kept) {
      if (covers(k.candidate(), candidate)) {
        return k;
      }
    }
    return null;
  }

  /**
   * True if {@code general}'s key columns start with all of {@code specific}'s (prefix or equal).
   */
  private static boolean covers(IndexCandidate general, IndexCandidate specific) {
    if (!general.table().equalsIgnoreCase(specific.table())
        || general.accessMethod() != specific.accessMethod()) {
      return false;
    }
    List<String> g = general.columns();
    List<String> s = specific.columns();
    if (s.size() > g.size()) {
      return false;
    }
    for (int i = 0; i < s.size(); i++) {
      if (!g.get(i).equalsIgnoreCase(s.get(i))) {
        return false;
      }
    }
    return true;
  }

  private static double orZero(Double d) {
    return d == null ? 0.0 : d;
  }

  /** Mutable working row: a weighted rec, its score, and the index that subsumes it (if any). */
  private static final class Scored {
    final Weighted weighted;
    final double score;
    String subsumedBy;

    Scored(Weighted weighted, double score) {
      this.weighted = weighted;
      this.score = score;
    }

    IndexCandidate candidate() {
      return weighted.recommendation().candidate();
    }
  }
}
