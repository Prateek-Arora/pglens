package com.pglens.engine.hygiene;

/**
 * One index-hygiene finding: an existing index worth <em>reviewing for removal</em> (Phase 2, Step
 * 6, ADR-0029). Advisory only — PgLens surfaces the evidence and never auto-drops anything, and by
 * construction it is never emitted for a {@link com.pglens.engine.model.IndexInfo#guarded()
 * guarded} (unique / PK / FK / constraint-backing) index. Pure model — no I/O.
 *
 * @param table the table the index belongs to
 * @param indexName the flagged index
 * @param definition its {@code pg_get_indexdef} DDL (for display)
 * @param kind why it was flagged
 * @param relatedIndex the surviving / covering index for {@code DUPLICATE} / {@code REDUNDANT};
 *     null for {@code UNUSED}
 * @param reason a human-readable evidence sentence built from real catalog / window facts
 */
public record IndexHygieneFinding(
    String table,
    String indexName,
    String definition,
    Kind kind,
    String relatedIndex,
    String reason) {

  /** The hygiene issue. At most one is reported per index, in this priority order. */
  public enum Kind {
    /** An exact structural twin of another index (same columns, method, predicate). */
    DUPLICATE,
    /** Its key columns are a leading prefix of a wider index that already serves them. */
    REDUNDANT,
    /** Not scanned once across the observed snapshot window. */
    UNUSED
  }
}
