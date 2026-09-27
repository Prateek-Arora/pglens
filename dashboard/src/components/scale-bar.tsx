import { cn } from "@/lib/utils";

export type Metric = "total" | "mean" | "calls";

/** Each metric keeps one ink everywhere: bars, sort links, columns, totals, charts (ADR-0048). */
export const METRIC_INK: Record<Metric, string> = {
  total: "text-metric-total",
  mean: "text-metric-mean",
  calls: "text-metric-calls",
};

/**
 * A dimension line drawn to scale: `value / max` of the track, solid for a measurement and hatched
 * for a planner estimate, closed by an end tick like a drawing's dimension. Decorative — the number
 * it draws is always printed beside it — so it is hidden from assistive tech.
 */
export function ScaleBar({
  value,
  max,
  ink,
  estimate = false,
  className,
}: {
  value: number | null;
  max: number;
  ink: string;
  estimate?: boolean;
  className?: string;
}) {
  const share = value === null || max <= 0 ? 0 : Math.min(1, value / max);
  const width = `max(${(share * 100).toFixed(2)}%, 2px)`;
  return (
    <span aria-hidden className={cn("relative block h-1.5", ink, className)}>
      <span className="bg-border absolute inset-x-0 top-1/2 h-px -translate-y-1/2" />
      {share > 0 && (
        <>
          <span
            className={cn("draw-in absolute inset-y-0 left-0", estimate ? "hatch" : "bg-current")}
            style={{ width }}
          />
          <span
            className="absolute -top-1 -bottom-1 w-px -translate-x-px bg-current"
            style={{ left: width }}
          />
        </>
      )}
    </span>
  );
}

/** A round step (1, 2, 2.5 or 5 × 10ⁿ) that splits `max` into at most four intervals. */
function niceStep(max: number): number {
  const raw = max / 4;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const unit = [1, 2, 2.5, 5, 10].find((m) => m * pow >= raw) ?? 10;
  return unit * pow;
}

/**
 * The scale the {@link ScaleBar}s above or below it are drawn to: graduations at round values of
 * the same unit, so a bar's length can be read, not just compared.
 */
export function ScaleRuler({
  max,
  format,
  className,
}: {
  max: number;
  format: (v: number) => string;
  className?: string;
}) {
  if (max <= 0) return null;
  const step = niceStep(max);
  const ticks: number[] = [];
  for (let v = 0; v <= max + 1e-9; v += step) ticks.push(v);
  return (
    <span
      aria-hidden
      className={cn("text-muted-foreground relative block h-5 border-b", className)}
    >
      {ticks.map((v) => {
        const left = `${((v / max) * 100).toFixed(2)}%`;
        return (
          <span key={v}>
            <span className="bg-border absolute bottom-0 h-1.5 w-px" style={{ left }} />
            <span
              className="absolute top-0 text-[11px] leading-none font-normal whitespace-nowrap tabular-nums"
              style={{ left, transform: v === 0 ? undefined : "translateX(-50%)" }}
            >
              {format(v)}
            </span>
          </span>
        );
      })}
    </span>
  );
}
