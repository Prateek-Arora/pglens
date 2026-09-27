import type { ReactNode } from "react";
import Link from "next/link";

import type { components } from "@/lib/api/schema";
import { Count, Measured } from "@/components/numbers";
import { ScaleBar } from "@/components/scale-bar";
import { SqlInline } from "@/components/sql";
import { formatCount, formatMs, formatPercent, formatUtc } from "@/lib/format";
import { routes } from "@/lib/routes";
import { cn } from "@/lib/utils";
import type { TimePoint } from "./time-chart";

type OverviewPoint = components["schemas"]["TimelinePoint"];
type DbPoint = components["schemas"]["DbTimelinePoint"];
type AppliedIndex = components["schemas"]["AppliedIndex"];
type AppliedQuery = components["schemas"]["AppliedQuery"];

/** The overview's hours as chart points (a null hour stays a gap). */
export function overviewPoints(points: OverviewPoint[]): TimePoint[] {
  return points.map((p) => ({
    t: Date.parse(p.hour),
    totalMs: p.measuredTotalMs,
    withAdviceMs: p.measuredMsWithAdvice,
    savedMs: p.estimatedMsSaved,
  }));
}

/** A database's hours as chart points (an unsampled hour is a gap, not a zero). */
export function dbPoints(points: DbPoint[]): TimePoint[] {
  return points.map((p) => ({
    t: Date.parse(p.hour),
    totalMs: p.sampled ? p.measuredTotalMs : null,
    withAdviceMs: p.sampled ? p.measuredMsWithAdvice : null,
    savedMs: p.sampled ? p.estimatedMsSaved : null,
  }));
}

/** The chart's key: a solid swatch for measured time, a hatched one for the estimate within it. */
export function ChartLegend() {
  return (
    <ul className="text-muted-foreground flex flex-wrap gap-x-5 gap-y-1 text-sm">
      <li className="flex items-center gap-2">
        <span aria-hidden className="bg-metric-total size-3" />
        Measured query time per hour
      </li>
      <li className="text-metric-total flex items-center gap-2">
        <span aria-hidden className="hatch size-3" />
        <span className="text-muted-foreground">
          …of which the planner estimates the suggested indexes would save
        </span>
      </li>
    </ul>
  );
}

/**
 * A headline figure over its caption, for the title strip of a sheet. `children` is the value —
 * always one of the labeled number components.
 */
export function Kpi({
  label,
  children,
  note,
  className,
}: {
  label: string;
  children: ReactNode;
  note?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("bg-card flex min-w-0 flex-col gap-1 px-5 py-4", className)}>
      <dt className="text-muted-foreground text-[13px]">{label}</dt>
      <dd className="text-2xl leading-tight font-semibold tracking-tight">{children}</dd>
      {note && <dd className="text-muted-foreground text-[13px] leading-snug">{note}</dd>}
    </div>
  );
}

/**
 * A query's measured time per hour as a small line, for small multiples. Server-rendered SVG, drawn
 * to its own scale (the printed total says how big); gaps where no data arrived.
 */
export function Sparkline({ values, label }: { values: (number | null)[]; label: string }) {
  const w = 240;
  const h = 40;
  const max = Math.max(0, ...values.map((v) => v ?? 0));
  const step = values.length > 1 ? w / (values.length - 1) : w;
  const segments: string[] = [];
  let current = "";
  values.forEach((v, i) => {
    if (v === null) {
      if (current) segments.push(current);
      current = "";
      return;
    }
    const x = (i * step).toFixed(1);
    const y = (h - 2 - (max > 0 ? v / max : 0) * (h - 4)).toFixed(1);
    current += `${current ? "L" : "M"}${x},${y}`;
  });
  if (current) segments.push(current);
  return (
    <svg
      viewBox={`0 0 ${w} ${h}`}
      preserveAspectRatio="none"
      className="text-metric-total h-10 w-full"
      role="img"
      aria-label={label}
    >
      <line x1="0" y1={h - 1} x2={w} y2={h - 1} className="stroke-border" strokeWidth="1" />
      {segments.map((d) => (
        <path
          key={d}
          d={d}
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          vectorEffect="non-scaling-stroke"
        />
      ))}
    </svg>
  );
}

/**
 * Changes within ±10 % read as "no clear change": a production before/after mixes in data, load
 * and cache changes, so a smaller move isn't evidence either way.
 */
const NOISE = 0.1;

/** A measured change of the mean: green when faster, redline when slower (DESIGN.md roles). */
function changeInk(change: number): string {
  if (change <= -NOISE) return "text-success";
  if (change >= NOISE) return "text-finding-foreground";
  return "text-muted-foreground";
}

/** "−46% faster" / "+12% slower" / "no clear change (+4%)". */
export function MeasuredChange({ change }: { change: number }) {
  const text =
    Math.abs(change) < NOISE
      ? `no clear change (${change < 0 ? "−" : "+"}${formatPercent(Math.abs(change))})`
      : `${change < 0 ? "−" : "+"}${formatPercent(Math.abs(change))} ${change < 0 ? "faster" : "slower"}`;
  return (
    <span data-kind="measured" className={cn("font-semibold whitespace-nowrap", changeInk(change))}>
      {text}
      <span className="sr-only"> (measured mean time per call)</span>
    </span>
  );
}

/** One query's before/after, drawn to one scale: two solid bars, both measured. */
function BeforeAfter({ q, database }: { q: AppliedQuery; database: string }) {
  const before = q.measuredMeanMsBefore;
  const after = q.measuredMeanMsAfter;
  const max = Math.max(before ?? 0, after ?? 0);
  return (
    <li className="space-y-2 py-3 first:pt-0 last:pb-0">
      <Link
        href={routes.query(database, q.queryid)}
        className="line-clamp-1 text-[13px] leading-5 underline-offset-4 hover:underline"
      >
        {q.sqlPreview ? <SqlInline sql={q.sqlPreview} /> : `Query ${q.queryid}`}
      </Link>
      {q.status === "MEASURED" && before !== null && after !== null ? (
        <div className="grid grid-cols-[4.5rem_1fr_auto] items-center gap-x-3 gap-y-1.5 text-sm">
          <span className="text-muted-foreground">Before</span>
          <ScaleBar value={before} max={max} ink="text-muted-foreground" className="h-2.5" />
          <span className="text-right">
            <Measured ms={before} what="Mean time per call in the 7 days before the index" />
          </span>
          <span className="text-muted-foreground">After</span>
          <ScaleBar
            value={after}
            max={max}
            ink={changeInk(q.measuredChangeFraction ?? 0)}
            className="h-2.5"
          />
          <span className="text-right">
            <Measured ms={after} what="Mean time per call since the index appeared" />
          </span>
          <span className="text-muted-foreground col-span-3 text-[13px]">
            <MeasuredChange change={q.measuredChangeFraction ?? 0} /> per call ·{" "}
            <Count value={q.callsBefore} what="Calls before" /> calls before,{" "}
            <Count value={q.callsAfter} what="Calls since" /> since
          </span>
        </div>
      ) : q.status === "MEASURING" ? (
        <p className="text-muted-foreground text-sm">
          Measuring: {formatCount(q.callsAfter)} of 10 calls since the index appeared (before:{" "}
          {before === null ? "no calls" : `${formatMs(before)} per call`}).
        </p>
      ) : (
        <p className="text-muted-foreground text-sm">
          Nothing to compare yet: {formatCount(q.callsBefore)} calls before the index appeared (10
          needed).
        </p>
      )}
    </li>
  );
}

/** A recommended index that now exists, with what happened to its queries. */
export async function AppliedIndexCard({
  applied,
  showDatabase = false,
}: {
  applied: AppliedIndex;
  showDatabase?: boolean;
}) {
  return (
    <article className="space-y-3">
      <header className="space-y-1">
        <p className="text-[13px] leading-5">
          <SqlInline sql={applied.ddl} />
        </p>
        <p className="text-muted-foreground text-[13px]">
          Built as <code className="font-mono">{applied.index}</code>
          {showDatabase && <> on {applied.database}</>} · PgLens saw it{" "}
          <time dateTime={applied.appliedAt}>{formatUtc(applied.appliedAt)}</time>
        </p>
      </header>
      <ul className="divide-y">
        {applied.queries.map((q) => (
          <BeforeAfter key={q.queryid} q={q} database={applied.database} />
        ))}
      </ul>
    </article>
  );
}
