import type { components } from "@/lib/api/schema";
import { Estimate } from "@/components/numbers";
import { ScaleBar } from "@/components/scale-bar";
import { cn } from "@/lib/utils";

type PlanNode = components["schemas"]["PlanNodeView"];
type Finding = components["schemas"]["FindingView"];
type Numbered = Finding & { n: number };

/**
 * The captured plan as a tree. A node a finding is about is highlighted, with the finding's
 * evidence beside it (the finding names the node by its pre-order id — `FindingView.planNode`).
 * Every number in a plan is the planner's estimate: the query was not run.
 */
export function PlanTree({
  plan,
  findings,
}: {
  plan: components["schemas"]["PlanView"];
  findings: Finding[];
}) {
  // Findings on a node, numbered in plan order like a drawing's redline balloons.
  const byNode = new Map<number, Numbered[]>();
  const placed = findings
    .filter((f) => f.planNode !== null)
    .sort((a, b) => (a.planNode ?? 0) - (b.planNode ?? 0));
  placed.forEach((f, i) => {
    const node = f.planNode as number;
    byNode.set(node, [...(byNode.get(node) ?? []), { ...f, n: i + 1 }]);
  });
  return (
    <div>
      <p className="text-muted-foreground mb-3 text-sm">{plan.label}</p>
      <ul className="font-mono text-sm">
        <Node node={plan.root} byNode={byNode} max={plan.root.estimatedTotalCost} />
      </ul>
    </div>
  );
}

/** One plan node; its hatched bar draws its planner cost to scale against the whole plan's. */
function Node({
  node,
  byNode,
  max,
}: {
  node: PlanNode;
  byNode: Map<number, Numbered[]>;
  max: number;
}) {
  const found = byNode.get(node.id) ?? [];
  const conditions = [
    ["Index cond", node.indexCond],
    ["Hash cond", node.hashCond],
    ["Recheck", node.recheckCond],
    ["Filter", node.filter],
    ["Sort", node.sortKeys.length ? node.sortKeys.join(", ") : null],
  ].filter((c): c is [string, string] => c[1] !== null);
  return (
    <li className="mt-1">
      <div
        id={`plan-node-${node.id}`}
        className={cn(
          "rounded-md border border-transparent px-2 py-1.5",
          found.length && "border-finding border-dashed",
        )}
      >
        <div className="flex flex-wrap items-baseline gap-x-2">
          <span className="font-semibold">
            {node.joinType ? `${node.joinType} ` : ""}
            {node.parallelAware ? "Parallel " : ""}
            {node.nodeType}
          </span>
          {node.relation && (
            <span>
              on {node.relation}
              {node.alias && node.alias !== relationName(node.relation) ? ` ${node.alias}` : ""}
            </span>
          )}
          {node.index && <span>using {node.index}</span>}
          {node.workersPlanned !== null && <span>({node.workersPlanned} workers planned)</span>}
          <span className="text-muted-foreground ml-auto flex items-center gap-3 font-sans text-xs">
            <ScaleBar
              value={node.estimatedTotalCost}
              max={max}
              ink={found.length ? "text-finding" : "text-muted-foreground/70"}
              estimate
              className="hidden h-2.5 w-28 sm:block"
            />
            <span>
              <Estimate value={node.estimatedRows} unit="rows" what="Rows the planner expects" /> ·
              cost{" "}
              <Estimate
                value={node.estimatedTotalCost}
                unit="cost"
                what="Planner cost up to this node (planner units, not milliseconds)"
              />
            </span>
          </span>
        </div>
        {conditions.map(([name, text]) => (
          <div key={name} className="text-muted-foreground wrap-anywhere">
            {name}: {text}
          </div>
        ))}
        {found.map((f) => (
          <p key={f.ruleId} className="text-finding-foreground mt-1.5 flex gap-2 font-sans">
            <span
              aria-hidden
              className="border-finding mt-px flex size-5 shrink-0 items-center justify-center rounded-full border text-[11px] font-semibold"
            >
              {f.n}
            </span>
            <span>
              <span className="sr-only">Problem {f.n}: </span>
              <strong>{f.title}.</strong> {f.evidence}
            </span>
          </p>
        ))}
      </div>
      {node.children.length > 0 && (
        <ul className="ml-4 border-l border-dashed pl-2">
          {node.children.map((c) => (
            <Node key={c.id} node={c} byNode={byNode} max={max} />
          ))}
        </ul>
      )}
    </li>
  );
}

/** The table's own name from its identity (`app."UserAccounts"` → `UserAccounts`), to compare with the alias. */
function relationName(relation: string): string {
  const last = relation.match(/(?:"((?:[^"]|"")+)"|([^."]+))$/);
  return last ? (last[1]?.replaceAll('""', '"') ?? last[2] ?? relation) : relation;
}
