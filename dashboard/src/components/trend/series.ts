import type { components } from "@/lib/api/schema";

type Point = components["schemas"]["TrendPointView"];
export type ChartPoint = {
  t: number;
  meanMs: number | null;
  totalMs: number | null;
  calls: number;
};

/**
 * Chart points with explicit gaps. An interval in which the query didn't run has no row at all
 * (ADR-0034), so two points far apart must not be joined by a line that suggests the query ran in
 * between: where the spacing is over 2.5× the usual step, a null point breaks the line.
 */
export function withGaps(points: Point[]): ChartPoint[] {
  const out: ChartPoint[] = [];
  const times = points.map((p) => Date.parse(p.capturedAt));
  const steps = times.slice(1).map((t, i) => t - times[i]!);
  const usual = median(steps);
  points.forEach((p, i) => {
    const t = times[i]!;
    const previous = times[i - 1];
    if (previous !== undefined && usual > 0 && t - previous > 2.5 * usual) {
      out.push({ t: previous + usual, meanMs: null, totalMs: null, calls: 0 });
    }
    out.push({ t, meanMs: p.measuredMeanMs, totalMs: p.measuredTotalMs, calls: p.calls });
  });
  return out;
}

function median(values: number[]): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)]!;
}
