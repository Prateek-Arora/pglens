package com.pglens.engine.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The estimated on-disk cost of a planner-validated index (ADR-0038): HypoPG's size estimate for
 * the hypothetical index and the table's current heap size. Their ratio is a proxy for the extra
 * work every write to the indexed columns does — an estimate, never a measurement. Pure model — no
 * I/O.
 */
public record IndexFootprint(long estimatedIndexBytes, long tableBytes, String label) {

  /** Index size as a fraction of the table's heap, or {@code null} for an empty table. */
  @JsonProperty("sizeRatio")
  public Double sizeRatio() {
    return tableBytes <= 0 ? null : (double) estimatedIndexBytes / tableBytes;
  }
}
