package com.pglens.engine.model;

/**
 * Which relative cost drop a recommendation's ranking score used (ADR-0038): the value-range worst
 * case (a conservative floor) or the generic plan's. Pure model — no I/O.
 */
public enum ScoreBasis {
  VALUE_RANGE_FLOOR,
  GENERIC_PLAN
}
