package com.pglens.engine.hygiene;

import java.time.Instant;

/**
 * How much an index's cumulative {@code idx_scan} counter grew across the persisted snapshot window
 * — the honest basis for "unused" (Phase 2, Step 6, ADR-0029). {@code scanDelta} is {@code last −
 * first} over {@code snapshots} snapshots spanning {@code from..to}. It is <b>only</b> meaningful
 * with real history behind it: the server builds one of these per index that has at least two
 * snapshots and whose counter did not go backwards (a backwards jump means a stats reset, so the
 * window can't be judged — that index gets no window and is never called unused). Pure value — no
 * I/O.
 */
public record IndexScanWindow(long scanDelta, int snapshots, Instant from, Instant to) {

  /** True if the index was not scanned once across the whole observed window. */
  public boolean unused() {
    return scanDelta == 0;
  }
}
