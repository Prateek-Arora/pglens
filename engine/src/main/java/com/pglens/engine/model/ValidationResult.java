package com.pglens.engine.model;

/**
 * The outcome of running a candidate through HypoPG. Costs are generic-plan planner estimates (not
 * runtime measurements); {@code relativeDelta} is {@code (before-after)/before}. Pure model — no
 * I/O.
 *
 * <p>{@code label} is the honesty sentence shown to the user — every number here is a HypoPG
 * planner estimate, and the label says so. A planner-validated verdict may also carry a {@link
 * ValueRangeEstimate} (how the win varies across real values) and an {@link IndexFootprint} (HypoPG
 * size estimate) — ADR-0038.
 */
public record ValidationResult(
    Status status,
    Double costBefore,
    Double costAfter,
    Double relativeDelta,
    boolean indexUsed,
    String label,
    ValueRangeEstimate valueRange,
    IndexFootprint footprint) {

  /** A verdict without the Phase 2.5 evidence (value range / footprint) — e.g. not validated. */
  public ValidationResult(
      Status status,
      Double costBefore,
      Double costAfter,
      Double relativeDelta,
      boolean indexUsed,
      String label) {
    this(status, costBefore, costAfter, relativeDelta, indexUsed, label, null, null);
  }

  /** This verdict with the value-range and footprint evidence attached (either may be null). */
  public ValidationResult withEvidence(ValueRangeEstimate range, IndexFootprint size) {
    return new ValidationResult(
        status, costBefore, costAfter, relativeDelta, indexUsed, label, range, size);
  }

  public enum Status {
    /** Hypothetical index was used in the re-planned tree and cost dropped past the threshold. */
    PLANNER_VALIDATED,
    /** Surfaced but HypoPG could not validate it (GIN/GiST, or hypopg absent, or no baseline). */
    NOT_PLANNER_VALIDATED,
    /** Validatable but rejected: the planner ignored it, or the win was below the threshold. */
    SUPPRESSED
  }

  public boolean isRecommended() {
    return status == Status.PLANNER_VALIDATED;
  }
}
