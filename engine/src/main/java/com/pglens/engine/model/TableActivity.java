package com.pglens.engine.model;

/**
 * Tuple-level read/write activity of one table over some window (ADR-0038): rows inserted, updated
 * and deleted ({@code pg_stat_user_tables.n_tup_ins/upd/del}) vs rows read by scans ({@code
 * seq_tup_read + idx_tup_fetch}). Real counters, never estimates. As read from the catalog these
 * are <em>cumulative</em> (since the database's stats were last reset); the server turns a
 * persisted series of them into a window delta. Pure model — no I/O.
 */
public record TableActivity(long inserted, long updated, long deleted, long tuplesRead) {

  public long tuplesWritten() {
    return inserted + updated + deleted;
  }
}
