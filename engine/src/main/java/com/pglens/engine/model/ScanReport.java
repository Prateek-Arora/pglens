package com.pglens.engine.model;

import java.util.List;

/**
 * The full result of a {@code pglens scan}, and the root of the {@code --json} contract that Phase
 * 2 consumes. Holds the target identity, one {@link QueryReport} per analyzed statement (with its
 * plan, findings, and per-candidate recommendations), the cross-query {@code topRecommendations}
 * (the planner-validated recs ranked and near-duplicate-deduped by the {@code Recommender}), and
 * the honesty {@code notes} (generic-plan / HypoPG caveats). Pure model — no I/O.
 *
 * <p>Contract 1.1 (ADR-0038, additive over 1.0): validations may carry {@code valueRange} and
 * {@code footprint}; ranked recs carry {@code scoreBasis}, {@code subsumedByDdl} and {@code
 * coverage}; and {@code tableWriteLoad} lists the write-load of each table that has a validated
 * recommendation.
 *
 * <p>Contract 1.2 (ADR-0041, additive over 1.1): a validation may carry {@code buildCaution} (a
 * B-tree whose key could hold a value too wide to index), and {@code target.port} is set (ADR-0042
 * — {@code pglens confirm} compares it with the copy). Also since 1.2, {@code scoreBasis} is always
 * {@code GENERIC_PLAN} — the value range is evidence, not the ranking drop.
 *
 * <p>Contract 1.3 (ADR-0043, additive over 1.2): the CLI's {@code --plain} adds a root {@code
 * explanations} array (one plain-language explanation per explained index, written by the {@code
 * :explain} module, which the engine doesn't depend on). Without {@code --plain} a 1.3 report is a
 * 1.2 report with a new version string.
 *
 * <p>Contract 1.4 (ADR-0044, additive over 1.3): each finding carries {@code planNode}, the
 * position of the node it is about in a pre-order walk of its query's plan (0 = the root), so a
 * plan viewer can highlight it.
 *
 * <p>Contract 1.5 (ADR-0049, additive over 1.4): a query whose plan couldn't be captured carries
 * {@code planError}, the reason; the notes name the schemas the role can't read, with the grants
 * that fix it. Tables are named by their identity ({@link SqlIdent#table}: schema-qualified unless
 * {@code public}, quoted when Postgres would quote them), and columns by their raw names.
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
    List<TableWriteLoad> tableWriteLoad,
    List<String> notes) {

  /**
   * The frozen {@code --json} contract version. Bump on any breaking field change (Phase 2 reads
   * it).
   */
  public static final String SCHEMA_VERSION = "1.5";

  public ScanReport {
    queries = queries == null ? List.of() : List.copyOf(queries);
    topRecommendations = topRecommendations == null ? List.of() : List.copyOf(topRecommendations);
    tableWriteLoad = tableWriteLoad == null ? List.of() : List.copyOf(tableWriteLoad);
    notes = notes == null ? List.of() : List.copyOf(notes);
  }
}
