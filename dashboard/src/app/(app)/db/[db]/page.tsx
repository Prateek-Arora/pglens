import type { Metadata } from "next";
import type { ReactNode } from "react";
import Link from "next/link";

import { ChartLegend, dbPoints } from "@/components/impact/impact";
import { TimeChart } from "@/components/impact/time-chart";
import { Count, Estimate, Measured } from "@/components/numbers";
import { StatusBadge } from "@/components/recommendations";
import { METRIC_INK, ScaleBar, ScaleRuler, type Metric } from "@/components/scale-bar";
import { SqlInline } from "@/components/sql";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ParamLinks, parseWindow } from "@/components/window-links";
import type { components } from "@/lib/api/schema";
import { api, read } from "@/lib/api/server";
import { formatCount, formatMs, formatUtc } from "@/lib/format";
import { routes } from "@/lib/routes";
import { cn } from "@/lib/utils";

const PAGE_SIZE = 25;
const SORTS = [
  { value: "total", text: "Total time", ink: METRIC_INK.total },
  { value: "mean", text: "Mean time", ink: METRIC_INK.mean },
  { value: "calls", text: "Calls", ink: METRIC_INK.calls },
] as const satisfies readonly { value: Metric; text: string; ink: string }[];
type Sort = (typeof SORTS)[number]["value"];
type Row = components["schemas"]["LeaderboardEntry"];

/** The value the board is sorted by, which its scale bars draw. */
const metricOf: Record<Sort, (q: Row) => number | null> = {
  total: (q) => q.measuredTotalMs,
  mean: (q) => q.measuredMeanMs,
  calls: (q) => q.calls,
};

export async function generateMetadata({ params }: PageProps<"/db/[db]">): Promise<Metadata> {
  return { title: `Slow queries · ${(await params).db}` };
}

export default async function LeaderboardPage({ params, searchParams }: PageProps<"/db/[db]">) {
  const { db } = await params;
  const sp = await searchParams;
  const window = parseWindow(sp.window);
  const sort: Sort = SORTS.find((s) => s.value === sp.sort)?.value ?? "total";
  const page = Math.max(1, Number.parseInt(String(sp.page ?? "1"), 10) || 1);

  const client = await api();
  const [board, timeline] = await Promise.all([
    read(
      client.GET("/api/v1/databases/{db}/queries", {
        params: {
          path: { db },
          query: { window, sort, limit: PAGE_SIZE, offset: (page - 1) * PAGE_SIZE },
        },
      }),
    ),
    read(
      client.GET("/api/v1/databases/{db}/timeline", {
        params: { path: { db }, query: { window, series: 1 } },
      }),
    ),
  ]);
  // Changing the window or sort starts again at page 1 (no `page` parameter).
  const href = (change: Record<string, string>) => {
    const next: Record<string, string | undefined> = { window, sort, ...change };
    return routes.database(db, { ...next, page: next.page === "1" ? undefined : next.page });
  };
  const pages = Math.max(1, Math.ceil(board.total / PAGE_SIZE));
  const metric = metricOf[sort];
  const max = Math.max(0, ...board.items.map((q) => metric(q) ?? 0));
  const rank = (i: number) => (page - 1) * PAGE_SIZE + i + 1;
  const sortedBy = SORTS.find((s) => s.value === sort)!;
  // The sorted column is printed in its metric's ink.
  // Each metric in its own ink everywhere; the sorted one also in bold.
  const inkFor = (m: Sort) => cn(METRIC_INK[m], m === sort && "font-semibold");
  const scaleFormat = sort === "calls" ? formatCount : formatMs;
  // Summed only when every query is on this page, so it is the window's real total.
  const windowTotal =
    pages === 1 ? board.items.reduce((sum, q) => sum + q.measuredTotalMs, 0) : null;

  return (
    <section aria-labelledby="leaderboard" className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <h2 id="leaderboard" className="mr-auto text-lg font-semibold">
          Where the time goes
        </h2>
        <ParamLinks
          label="Time window"
          param="window"
          current={window}
          href={href}
          options={[
            { value: "24h", text: "24 h" },
            { value: "7d", text: "7 days" },
            { value: "30d", text: "30 days" },
          ]}
        />
        <ParamLinks label="Sort by" param="sort" current={sort} href={href} options={SORTS} />
      </div>
      {/* The sheet's title strip: what was measured, over which window, from where. */}
      <dl className="sheet flex flex-wrap text-sm">
        <Field label="Queries">
          <Count value={board.total} what="Queries that ran in this window" />
        </Field>
        {windowTotal !== null && (
          <Field label="Total time">
            <span className={METRIC_INK.total}>
              <Measured
                ms={windowTotal}
                what="Total execution time of every query in this window"
              />
            </span>
          </Field>
        )}
        <Field label="Window (UTC, from the hour)" grow>
          {formatUtc(board.from)} → {formatUtc(board.to)}
        </Field>
        <Field label="Source">
          <code className="font-mono text-[0.92em]">pg_stat_statements</code>, measured
        </Field>
      </dl>
      {timeline.points.some((p) => p.sampled && p.measuredTotalMs > 0) && (
        <div className="sheet space-y-2 px-5 pt-4 pb-2">
          <ChartLegend />
          <TimeChart points={dbPoints(timeline.points)} height={200} />
        </div>
      )}
      {board.items.length === 0 ? (
        <div className="sheet p-6 text-sm">
          <p className="font-medium">No query activity in this window yet.</p>
          <p className="text-muted-foreground mt-1">
            Activity counts from the agent&apos;s first sample: a query&apos;s first sighting only
            anchors its counters. Run your workload or wait one sampling interval, then refresh.
            Index advice can already be ready —{" "}
            <Link href={routes.recommendations(db)} className="underline underline-offset-4">
              see the recommendations
            </Link>
            .
          </p>
        </div>
      ) : (
        <>
          {/* Phones: one stacked entry per query, so the SQL stays readable. */}
          <ol
            aria-label={`Queries by ${sortedBy.text.toLowerCase()}`}
            className="sheet divide-y md:hidden"
          >
            {board.items.map((q, i) => (
              <li key={q.queryid} className="space-y-2.5 px-4 py-3">
                <div className="flex gap-3">
                  <span className="text-muted-foreground w-5 shrink-0 pt-px text-xs">
                    {rank(i)}
                  </span>
                  <Link
                    href={routes.query(db, q.queryid, {
                      window,
                      sort: sort === "total" ? undefined : sort,
                    })}
                    className="line-clamp-3 min-w-0 text-[13px] leading-snug underline-offset-4 hover:underline"
                  >
                    <SqlInline sql={q.sqlPreview + (q.sqlPreviewCut ? "…" : "")} />
                  </Link>
                </div>
                <ScaleBar value={metric(q)} max={max} ink={METRIC_INK[sort]} className="ml-8" />
                <dl className="ml-8 flex flex-wrap gap-x-4 gap-y-1 text-sm">
                  <div className="flex gap-1.5">
                    <dt className="text-muted-foreground">Total</dt>
                    <dd className={inkFor("total")}>
                      <Measured ms={q.measuredTotalMs} what="Total execution time in this window" />
                    </dd>
                  </div>
                  <div className="flex gap-1.5">
                    <dt className="text-muted-foreground">Mean</dt>
                    <dd className={inkFor("mean")}>
                      <Measured ms={q.measuredMeanMs} what="Mean execution time per call" />
                    </dd>
                  </div>
                  <div className="flex gap-1.5">
                    <dt className="text-muted-foreground">Calls</dt>
                    <dd className={inkFor("calls")}>
                      <Count value={q.calls} what="Calls in this window" />
                    </dd>
                  </div>
                  <div className="flex gap-1.5">
                    <dt className="text-muted-foreground">Rows</dt>
                    <dd>
                      <Count value={q.rows} what="Rows returned or affected in this window" />
                    </dd>
                  </div>
                </dl>
                {q.recommendation ? (
                  <div className="ml-8 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
                    <StatusBadge status={q.recommendation} />
                    {q.estimatedMsSaved !== null && <Saved ms={q.estimatedMsSaved} />}
                  </div>
                ) : null}
              </li>
            ))}
          </ol>
          <div className="sheet hidden overflow-hidden md:block">
            <Table>
              <TableHeader className="bg-muted/60">
                <TableRow className="hover:bg-transparent">
                  <TableHead scope="col" className="w-10 pl-4 text-right">
                    <span className="sr-only">Rank</span>#
                  </TableHead>
                  <TableHead scope="col" className="py-2 align-bottom">
                    <span className="block">Query</span>
                    <ScaleRuler
                      max={max}
                      format={scaleFormat}
                      className="mt-1 max-w-md font-normal"
                    />
                  </TableHead>
                  <TableHead scope="col" className={cn("text-right", inkFor("total"))}>
                    Total time
                  </TableHead>
                  <TableHead scope="col" className={cn("text-right", inkFor("mean"))}>
                    Mean time
                  </TableHead>
                  <TableHead scope="col" className={cn("text-right", inkFor("calls"))}>
                    Calls
                  </TableHead>
                  <TableHead scope="col" className="text-right">
                    Rows
                  </TableHead>
                  <TableHead scope="col" className="pr-4">
                    Index advice
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {board.items.map((q, i) => (
                  <TableRow
                    key={q.queryid}
                    className="hover:bg-accent/60 [&>td]:py-3 [&>td]:leading-5"
                  >
                    <TableCell className="text-muted-foreground pl-4 text-right align-top text-xs leading-5">
                      {rank(i)}
                    </TableCell>
                    <TableCell className="w-full max-w-0 min-w-80 py-3 whitespace-normal">
                      <Link
                        href={routes.query(db, q.queryid, {
                          window,
                          sort: sort === "total" ? undefined : sort,
                        })}
                        className="line-clamp-2 text-[13px] leading-5 underline-offset-4 hover:underline"
                      >
                        <SqlInline sql={q.sqlPreview + (q.sqlPreviewCut ? "…" : "")} />
                      </Link>
                      <ScaleBar
                        value={metric(q)}
                        max={max}
                        ink={METRIC_INK[sort]}
                        className="mt-2 max-w-md"
                      />
                    </TableCell>
                    <TableCell className={cn("text-right align-top", inkFor("total"))}>
                      <Measured ms={q.measuredTotalMs} what="Total execution time in this window" />
                    </TableCell>
                    <TableCell className={cn("text-right align-top", inkFor("mean"))}>
                      <Measured ms={q.measuredMeanMs} what="Mean execution time per call" />
                    </TableCell>
                    <TableCell className={cn("text-right align-top", inkFor("calls"))}>
                      <Count value={q.calls} what="Calls in this window" />
                    </TableCell>
                    <TableCell className="text-right align-top">
                      <Count value={q.rows} what="Rows returned or affected in this window" />
                    </TableCell>
                    <TableCell className="pr-4 align-top">
                      {q.recommendation ? (
                        <div className="flex flex-col items-start gap-1 text-sm">
                          <StatusBadge status={q.recommendation} />
                          {q.estimatedMsSaved !== null && <Saved ms={q.estimatedMsSaved} />}
                        </div>
                      ) : null}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </>
      )}

      {pages > 1 && (
        <nav aria-label="Pages" className="flex items-center gap-3 text-sm">
          {page > 1 ? (
            <Button asChild variant="outline" size="sm">
              <Link href={href({ page: String(page - 1) })}>Previous</Link>
            </Button>
          ) : null}
          <span>
            Page {page} of {pages}
          </span>
          {page < pages ? (
            <Button asChild variant="outline" size="sm">
              <Link href={href({ page: String(page + 1) })}>Next</Link>
            </Button>
          ) : null}
        </nav>
      )}
    </section>
  );
}

/** The window's measured time × the planner's drop for the query's best index. */
function Saved({ ms }: { ms: number }) {
  return (
    <span className="text-muted-foreground whitespace-nowrap">
      <span className="text-foreground">
        <Estimate
          value={ms}
          unit="ms"
          what="This window's measured time scaled by the planner's cost drop for the best index — not a measured speedup"
        />
      </span>{" "}
      saved
    </span>
  );
}

/** One ruled cell of the title strip: a small caption over its value. */
function Field({
  label,
  grow = false,
  children,
}: {
  label: string;
  grow?: boolean;
  children: ReactNode;
}) {
  return (
    <div
      className={cn(
        "flex min-w-0 flex-col gap-0.5 border-r px-4 py-2.5 last:border-r-0 max-sm:basis-1/2 max-sm:border-b",
        grow && "grow",
      )}
    >
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd className="font-medium">{children}</dd>
    </div>
  );
}
