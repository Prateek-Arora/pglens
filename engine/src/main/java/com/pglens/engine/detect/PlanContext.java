package com.pglens.engine.detect;

import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.PlanNode;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bundles a parsed plan with the catalog and a qualifier→table resolver, shared across rules. The
 * resolver is built once by walking the scan nodes (each carries {@code Schema}, {@code Relation
 * Name} and {@code Alias}), so a rule can turn a {@code o.customer_id} reference back into the real
 * table's identity ({@code orders}, {@code app."UserAccounts"} — see {@link
 * com.pglens.engine.model.SqlIdent#table}).
 */
final class PlanContext {

  final PlanNode plan;
  final CatalogSnapshot catalog;
  private final Map<String, String> qualifierToTable = new HashMap<>();
  private final Map<PlanNode, Integer> nodeIds = new IdentityHashMap<>();

  PlanContext(PlanNode plan, CatalogSnapshot catalog) {
    this.plan = plan;
    this.catalog = catalog;
    for (PlanNode node : plan.flatten()) {
      nodeIds.put(node, nodeIds.size());
      String table = node.table();
      if (table != null) {
        // An alias wins over a same-named relation (EXPLAIN aliases every repeated relation).
        qualifierToTable.putIfAbsent(node.relationName().toLowerCase(Locale.ROOT), table);
        if (node.alias() != null) {
          qualifierToTable.put(node.alias().toLowerCase(Locale.ROOT), table);
        }
      }
    }
  }

  /** A node's position in {@code plan.flatten()} — the id a {@code Finding} points at. */
  Integer nodeId(PlanNode node) {
    return nodeIds.get(node);
  }

  /** Resolve a raw qualifier (table name or alias) to its table's identity, or null if unknown. */
  String resolveTable(String qualifier) {
    return qualifier == null ? null : qualifierToTable.get(qualifier.toLowerCase(Locale.ROOT));
  }

  /**
   * The table an index for {@code table} is created on: the root of its partition tree for a
   * partition (an index on the parent covers every partition, present and future), else itself.
   */
  String indexTarget(String table) {
    return catalog.partitionRoot(table).orElse(table);
  }
}
