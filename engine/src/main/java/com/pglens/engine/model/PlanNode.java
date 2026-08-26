package com.pglens.engine.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * One node in an EXPLAIN plan tree, typed. Fields map to EXPLAIN (FORMAT JSON) keys. Parallelism is
 * a flag ({@code parallelAware}) — a parallel table scan is node type {@code "Seq Scan"} with
 * {@code parallelAware=true}, not a distinct type. The ANALYZE-only fields ({@code actualRows},
 * {@code rowsRemovedByFilter}, {@code sortMethod}) are null under GENERIC_PLAN.
 *
 * <p>Pure model type — no Spring, no I/O.
 */
public record PlanNode(
    String nodeType,
    boolean parallelAware,
    double startupCost,
    double totalCost,
    long planRows,
    int planWidth,
    String relationName,
    String alias,
    String indexName,
    String joinType,
    String filter,
    String indexCond,
    String recheckCond,
    String hashCond,
    List<String> sortKeys,
    List<String> output,
    Integer workersPlanned,
    Long actualRows,
    Long rowsRemovedByFilter,
    String sortMethod,
    List<PlanNode> children) {

  public PlanNode {
    sortKeys = sortKeys == null ? List.of() : List.copyOf(sortKeys);
    output = output == null ? List.of() : List.copyOf(output);
    children = children == null ? List.of() : List.copyOf(children);
  }

  public boolean isSeqScan() {
    return "Seq Scan".equals(nodeType);
  }

  public boolean isJoin() {
    return "Nested Loop".equals(nodeType)
        || "Hash Join".equals(nodeType)
        || "Merge Join".equals(nodeType);
  }

  public boolean isSort() {
    return "Sort".equals(nodeType) || "Incremental Sort".equals(nodeType);
  }

  /** This node and all descendants in pre-order (depth-first, left to right). */
  public List<PlanNode> flatten() {
    List<PlanNode> all = new ArrayList<>();
    Deque<PlanNode> stack = new ArrayDeque<>();
    stack.push(this);
    while (!stack.isEmpty()) {
      PlanNode node = stack.pop();
      all.add(node);
      for (int i = node.children.size() - 1; i >= 0; i--) {
        stack.push(node.children.get(i));
      }
    }
    return all;
  }
}
