package com.pglens.agent.sample;

import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.IndexInfo;
import com.pglens.engine.model.TableInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * When a query's stored plan is out of date (ADR-0051). A plan is captured when a query is first
 * seen; it goes stale when the database's indexes change — most importantly when someone builds the
 * index PgLens recommended, which should retire the advice and show the new plan — and slowly as
 * the data changes. So a known query's plan is re-captured when the index set changes, and at least
 * every {@code maxAgeMs}. Pure bookkeeping; the collector does the capturing.
 */
final class PlanRefresh {

  private final long maxAgeMs;
  private final Map<Long, Long> capturedAtMs = new ConcurrentHashMap<>();
  private String indexFingerprint;

  PlanRefresh(long maxAgeMs) {
    this.maxAgeMs = maxAgeMs;
  }

  /**
   * Records this cycle's catalog. When its indexes differ from the previous cycle's, every known
   * plan becomes due, and stays due until the server has received it again (so a failed send
   * doesn't lose the re-capture). Returns whether they changed — never on the first cycle.
   */
  boolean observe(CatalogSnapshot catalog) {
    String fingerprint = fingerprint(catalog);
    boolean changed = indexFingerprint != null && !indexFingerprint.equals(fingerprint);
    indexFingerprint = fingerprint;
    if (changed) {
      capturedAtMs.clear();
    }
    return changed;
  }

  /** True when a known query's stored plan should be captured again this cycle. */
  boolean due(long queryId, long nowMs) {
    Long at = capturedAtMs.get(queryId);
    return at == null || nowMs - at >= maxAgeMs;
  }

  /** The server has {@code queryId}'s plan as captured at {@code nowMs}. */
  void captured(long queryId, long nowMs) {
    capturedAtMs.put(queryId, nowMs);
  }

  /** Every index's table, name, key, method and predicate, in a stable order. */
  static String fingerprint(CatalogSnapshot catalog) {
    List<String> parts = new ArrayList<>();
    for (TableInfo table : catalog.tables().values()) {
      for (IndexInfo ix : table.indexes()) {
        parts.add(
            String.join(
                "\u0001",
                ix.table(),
                ix.name(),
                String.join(",", ix.columns()),
                String.valueOf(ix.method()),
                String.valueOf(ix.predicate())));
      }
    }
    parts.sort(null);
    return String.join("\u0002", parts);
  }
}
