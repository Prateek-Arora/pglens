package com.pglens.engine.model;

/**
 * Which relative cost drop a recommendation's ranking score used. Since ADR-0041 every score is
 * {@link #GENERIC_PLAN}; {@link #VALUE_RANGE_FLOOR} (the value-range worst case, ADR-0038) was only
 * produced by v0.0.4 and stays so its persisted rows and {@code --json} 1.1 readers still parse.
 * Pure model — no I/O.
 */
public enum ScoreBasis {
  VALUE_RANGE_FLOOR,
  GENERIC_PLAN
}
