package com.pglens.engine.model;

/**
 * The outcome of running a candidate through HypoPG. Costs are generic-plan planner estimates (not
 * runtime measurements); {@code relativeDelta} is {@code (before-after)/before}. Pure model — no
 * I/O.
 *
 * <p>{@code label} is the honesty sentence shown to the user — every number here is a HypoPG
 * planner estimate, and the label says so.
 */
public record ValidationResult(
    Status status,
    Double costBefore,
    Double costAfter,
    Double relativeDelta,
    boolean indexUsed,
    String label) {

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
