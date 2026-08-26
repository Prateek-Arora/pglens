package com.pglens.engine.model;

import java.util.List;

/**
 * The full result of a {@code pglens scan}, and the root of the {@code --json} contract that Phase
 * 2 consumes. Holds the target identity, one {@link QueryReport} per analyzed statement (with its
 * plan, findings, and per-candidate recommendations), the cross-query {@code topRecommendations}
 * (the planner-validated recs ranked and near-duplicate-deduped by the {@code Recommender}), and
 * the honesty {@code notes} (generic-plan / HypoPG caveats). Pure model — no I/O.
 *
 * <p>A validated recommendation appears twice by design: once under its query ({@link
 * QueryReport#recommendations()}, the full per-query story) and once, ranked and scored, in {@link
 * #topRecommendations()} (the cross-query "indexes to create" summary).
 */
public record ScanReport(
    String schemaVersion,
    String generatedAt,
    TargetInfo target,
    List<QueryReport> queries,
    List<RankedRecommendation> topRecommendations,
    List<String> notes) {

  /**
   * The frozen {@code --json} contract version. Bump on any breaking field change (Phase 2 reads
   * it).
   */
  public static final String SCHEMA_VERSION = "1.0";

  public ScanReport {
    queries = queries == null ? List.of() : List.copyOf(queries);
    topRecommendations = topRecommendations == null ? List.of() : List.copyOf(topRecommendations);
    notes = notes == null ? List.of() : List.copyOf(notes);
  }
}
