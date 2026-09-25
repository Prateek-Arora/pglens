package com.pglens.engine.confirm;

import com.pglens.engine.model.TargetInfo;
import java.util.List;

/**
 * The result of {@code pglens confirm} and the root of its {@code --json} contract (ADR-0042): the
 * scan report it checked, the copy it measured on, the settings, how many workload statements were
 * read and matched, one {@link IndexConfirmation} per recommended index in rank order, and the
 * honesty notes. Every time in it was <b>measured on the copy</b>; every estimate is PgLens's
 * HypoPG generic-plan estimate from the scan report. No statement text or value appears in it. Pure
 * model — no I/O.
 */
public record ConfirmReport(
    String schemaVersion,
    String generatedAt,
    boolean dryRun,
    String scanGeneratedAt,
    TargetInfo scanTarget,
    TargetInfo copy,
    Settings settings,
    StatementCounts statements,
    List<IndexConfirmation> indexes,
    List<String> notes) {

  /** The frozen {@code confirm --json} contract version. Bump on any breaking field change. */
  public static final String SCHEMA_VERSION = "1.0";

  public ConfirmReport {
    indexes = indexes == null ? List.of() : List.copyOf(indexes);
    notes = notes == null ? List.of() : List.copyOf(notes);
  }

  /** How the measurement ran. */
  public record Settings(
      int top, int perQuery, int runs, long statementTimeoutMs, double fasterAt, double slowerAt) {}

  /**
   * What happened to the workload's statements: read (kept: read-only), skipped (not a read, or
   * {@code $N} placeholders with no logged values), unusable on the copy (EXPLAIN failed there),
   * distinct query shapes, shapes matched to a report query this run checks, and statements
   * measured.
   */
  public record StatementCounts(
      int read,
      int skippedNotRead,
      int skippedNoValues,
      int unusable,
      int shapes,
      int shapesMatched,
      int statementsMeasured) {}
}
