package com.pglens.engine.detect;

import com.pglens.engine.model.CatalogSnapshot;
import com.pglens.engine.model.PlanNode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bundles a parsed plan with the catalog and a qualifier→table resolver, shared across rules. The
 * resolver is built once by walking the scan nodes (each carries {@code Relation Name} + {@code
 * Alias}), so a rule can turn a {@code o.customer_id} reference back into the real {@code orders}
 * table.
 */
final class PlanContext {

  final PlanNode plan;
  final CatalogSnapshot catalog;
  private final Map<String, String> qualifierToTable = new HashMap<>();

  PlanContext(PlanNode plan, CatalogSnapshot catalog) {
    this.plan = plan;
    this.catalog = catalog;
    for (PlanNode node : plan.flatten()) {
      String table = node.relationName();
      if (table != null) {
        qualifierToTable.put(table.toLowerCase(Locale.ROOT), table);
        if (node.alias() != null) {
          qualifierToTable.put(node.alias().toLowerCase(Locale.ROOT), table);
        }
      }
    }
  }

  /** Resolve a qualifier (table name or alias) to its real table name, or null if unknown. */
  String resolveTable(String qualifier) {
    return qualifier == null ? null : qualifierToTable.get(qualifier.toLowerCase(Locale.ROOT));
  }
}
