package com.pglens.engine.model;

/**
 * A table's write-load classification over a window, with the real counts behind it and a human
 * label (ADR-0038). Built by {@code hygiene.WriteLoad}. Pure model — no I/O.
 */
public record TableWriteLoad(
    String table, Level level, TableActivity activity, String window, String label) {

  public enum Level {
    /** More tuples written than read over the window — weigh an index's maintenance cost. */
    WRITE_DOMINANT,
    READ_DOMINANT,
    /** No activity recorded in the window (or no counters) — say nothing rather than guess. */
    NO_ACTIVITY
  }
}
