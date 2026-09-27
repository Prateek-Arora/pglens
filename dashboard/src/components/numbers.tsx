import type { ReactNode } from "react";

import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { formatCost, formatCount, formatMs, formatPercent } from "@/lib/format";

/**
 * Every number on screen goes through one of these (charter principle 1: every number is real and
 * labeled). The kind travels with the value: a tooltip with the full sentence, a screen-reader
 * suffix, a `data-kind` attribute, and — for estimates — a visible "est." and a dashed underline
 * (`.estimate-mark`) so a planner estimate is never mistaken for a measurement, which stays plain
 * solid ink. Charts use the same {@link KIND_LABEL} text.
 */
export type NumberKind = "measured" | "estimate" | "count";

export const KIND_LABEL: Record<NumberKind, string> = {
  measured: "Measured: execution time recorded by pg_stat_statements on your database.",
  estimate: "Estimate: from HypoPG planner estimates, not a measured runtime.",
  count: "Counted by pg_stat_statements on your database.",
};

const SHORT: Record<NumberKind, string> = {
  measured: "measured",
  estimate: "planner estimate",
  count: "counted",
};

function Labeled({
  kind,
  detail,
  children,
}: {
  kind: NumberKind;
  detail: ReactNode;
  children: ReactNode;
}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span data-kind={kind} className="whitespace-nowrap tabular-nums">
          {children}
          <span className="sr-only"> ({SHORT[kind]})</span>
        </span>
      </TooltipTrigger>
      <TooltipContent className="max-w-xs">{detail}</TooltipContent>
    </Tooltip>
  );
}

/** A measured duration. `null` = nothing to measure (say why in `none`). */
export function Measured({
  ms,
  what = "Execution time",
  none = "No calls in this window, so nothing was measured.",
}: {
  ms: number | null;
  what?: string;
  none?: string;
}) {
  if (ms === null) {
    return (
      <Labeled kind="measured" detail={none}>
        —
      </Labeled>
    );
  }
  return (
    <Labeled kind="measured" detail={`${what}. ${KIND_LABEL.measured}`}>
      {formatMs(ms)}
    </Labeled>
  );
}

/**
 * A number derived from the planner. `ms`: measured time scaled by a planner cost drop (ranking
 * only); `cost`: the planner's own unit; `drop`: a planner cost reduction as a fraction; `rows`:
 * the planner's row-count guess.
 */
export function Estimate({
  value,
  unit,
  what,
  none = "Not estimated.",
}: {
  value: number | null;
  unit: "ms" | "cost" | "drop" | "rows";
  what: string;
  none?: string;
}) {
  if (value === null) {
    return (
      <Labeled kind="estimate" detail={none}>
        —
      </Labeled>
    );
  }
  const text = {
    ms: () => `≈ ${formatMs(value)}`,
    cost: () => formatCost(value),
    drop: () => `−${formatPercent(value)}`,
    rows: () => `${formatCount(value)} rows`,
  }[unit]();
  return (
    <Labeled kind="estimate" detail={`${what}. ${KIND_LABEL.estimate}`}>
      <span className="estimate-mark">{text}</span>
      <span aria-hidden className="text-muted-foreground ml-1 text-[0.8em] font-normal">
        est.
      </span>
    </Labeled>
  );
}

/** An exact count, e.g. calls or rows. */
export function Count({ value, what }: { value: number; what: string }) {
  return (
    <Labeled kind="count" detail={`${what}. ${KIND_LABEL.count}`}>
      {formatCount(value)}
    </Labeled>
  );
}
