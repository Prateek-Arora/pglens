"use client";

import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  type TooltipContentProps,
} from "recharts";

import { KIND_LABEL } from "@/components/numbers";
import { formatCount, formatMs, formatUtc } from "@/lib/format";
import type { ChartPoint } from "./series";

export const MEAN_AXIS_LABEL = "Mean time per call (measured)";

/**
 * A query's measured mean time over time. The y-axis starts at zero so a small change doesn't look
 * like a big one; gaps are where the query didn't run (see `withGaps`).
 */
export function TrendChart({ points, height = 260 }: { points: ChartPoint[]; height?: number }) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <LineChart data={points} margin={{ top: 8, right: 16, bottom: 8, left: 16 }}>
        <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
        <XAxis
          dataKey="t"
          type="number"
          scale="time"
          domain={["dataMin", "dataMax"]}
          tickFormatter={(t: number) => formatUtc(new Date(t).toISOString()).slice(5, 16)}
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
          width={84}
          label={{
            value: "Mean per call",
            angle: -90,
            position: "insideLeft",
            fontSize: 12,
            fill: "var(--muted-foreground)",
            style: { textAnchor: "middle" },
          }}
        />
        <Tooltip content={TrendTooltip} />
        <Line
          type="linear"
          dataKey="meanMs"
          name={MEAN_AXIS_LABEL}
          stroke="var(--metric-mean)"
          strokeWidth={2}
          dot={points.length < 60 ? { r: 3, fill: "var(--card)", strokeWidth: 2 } : false}
          connectNulls={false}
          isAnimationActive={false}
        />
      </LineChart>
    </ResponsiveContainer>
  );
}

function TrendTooltip({ active, payload }: TooltipContentProps) {
  const p = payload?.[0]?.payload as ChartPoint | undefined;
  if (!active || !p || p.meanMs === null) return null;
  return (
    <div className="bg-popover text-popover-foreground rounded-md border p-2 text-xs shadow-(--sheet-shadow)">
      <div className="font-medium">{formatUtc(new Date(p.t).toISOString())}</div>
      <div>
        Mean {formatMs(p.meanMs)} · {formatCount(p.calls)} calls
      </div>
      <div className="text-muted-foreground max-w-56">{KIND_LABEL.measured}</div>
    </div>
  );
}
