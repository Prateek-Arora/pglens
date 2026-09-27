"use client";

import { useId } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  type TooltipContentProps,
} from "recharts";

import { KIND_LABEL } from "@/components/numbers";
import { formatMs, formatUtc } from "@/lib/format";

/** One UTC hour. `null` values are hours with no data: no column, never a zero. */
export type TimePoint = {
  t: number;
  totalMs: number | null;
  withAdviceMs: number | null;
  savedMs: number | null;
};

type Row = TimePoint & { saved: number | null; rest: number | null };

/**
 * Measured query time per UTC hour, one column per hour in the total-time ink. The column's lower
 * part is hatched: the planner's estimate of what the suggested indexes would save of that hour's
 * time — the drafting convention for an estimate (DESIGN.md). One y-axis: both are milliseconds of
 * the same queries, and the estimate is never more than the measured time it scales.
 */
export function TimeChart({ points, height = 240 }: { points: TimePoint[]; height?: number }) {
  const hatch = `hatch-${useId().replaceAll(":", "")}`;
  const days = points.length > 48;
  const rows: Row[] = points.map((p) => {
    const saved = p.totalMs === null ? null : Math.min(p.savedMs ?? 0, p.totalMs);
    return {
      ...p,
      saved,
      rest: p.totalMs === null || saved === null ? null : p.totalMs - saved,
    };
  });
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: 0 }} barCategoryGap="12%">
        <defs>
          <pattern
            id={hatch}
            width="5"
            height="5"
            patternUnits="userSpaceOnUse"
            patternTransform="rotate(45)"
          >
            <line x1="0" y1="0" x2="0" y2="5" stroke="var(--metric-total)" strokeWidth="1.5" />
          </pattern>
        </defs>
        <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
        <XAxis
          dataKey="t"
          tickFormatter={(t: number) => {
            const iso = new Date(t).toISOString();
            return days ? iso.slice(5, 10) : iso.slice(11, 16);
          }}
          minTickGap={28}
          fontSize={12}
          tick={{ fill: "var(--muted-foreground)" }}
          stroke="var(--border)"
        />
        <YAxis
          domain={[0, "auto"]}
          tickFormatter={(ms: number) => formatMs(ms)}
          fontSize={12}
          tick={{ fill: "var(--muted-foreground)" }}
          stroke="var(--border)"
          width={64}
        />
        <Tooltip content={ChartTooltip} cursor={{ fill: "var(--accent)", opacity: 0.6 }} />
        <Bar
          dataKey="saved"
          stackId="hour"
          name="Planner-estimated saving"
          fill={`url(#${hatch})`}
          stroke="var(--metric-total)"
          strokeWidth={0.75}
          isAnimationActive={false}
        />
        <Bar
          dataKey="rest"
          stackId="hour"
          name="Measured query time"
          fill="var(--metric-total)"
          isAnimationActive={false}
        />
      </BarChart>
    </ResponsiveContainer>
  );
}

function ChartTooltip({ active, payload }: TooltipContentProps) {
  const p = payload?.[0]?.payload as Row | undefined;
  if (!active || !p) return null;
  const hour = new Date(p.t).toISOString();
  return (
    <div className="bg-popover text-popover-foreground max-w-64 space-y-1 rounded-md border p-2 text-xs shadow-(--sheet-shadow)">
      <div className="font-medium">
        {formatUtc(hour)} – {hour.slice(11, 13)}:59
      </div>
      {p.totalMs === null ? (
        <div className="text-muted-foreground">The agent didn&apos;t report this hour.</div>
      ) : (
        <>
          <div>
            Measured <span className="text-metric-total font-medium">{formatMs(p.totalMs)}</span>
            {p.withAdviceMs
              ? `, ${formatMs(p.withAdviceMs)} of it in queries with an index to try`
              : ""}
          </div>
          {p.saved ? (
            <div>
              Planner-estimated saving{" "}
              <span className="estimate-mark font-medium">≈ {formatMs(p.saved)}</span> est.
            </div>
          ) : null}
          <div className="text-muted-foreground">{KIND_LABEL.measured}</div>
        </>
      )}
    </div>
  );
}
