import type { Metadata } from "next";
import Link from "next/link";

import { ArrowDownRightIcon, ArrowUpRightIcon } from "lucide-react";

import { ChartLegend, dbPoints, Sparkline } from "@/components/impact/impact";
import { TimeChart } from "@/components/impact/time-chart";
import { Count, Estimate, Measured } from "@/components/numbers";
import { METRIC_INK } from "@/components/scale-bar";
import { SqlInline } from "@/components/sql";
import { ParamLinks, parseWindow } from "@/components/window-links";
import { api, read } from "@/lib/api/server";
import { formatMs, formatPercent, formatUtc } from "@/lib/format";
import { routes } from "@/lib/routes";
import { cn } from "@/lib/utils";

export async function generateMetadata({
  params,
}: PageProps<"/db/[db]/trends">): Promise<Metadata> {
  return { title: `Trends · ${(await params).db}` };
}

export default async function TrendsPage({ params, searchParams }: PageProps<"/db/[db]/trends">) {
  const { db } = await params;
  const window = parseWindow((await searchParams).window, "7d");
  const client = await api();
  const [movers, fresh, timeline] = await Promise.all([
    read(
      client.GET("/api/v1/databases/{db}/top-movers", {
        params: { path: { db }, query: { window, limit: 10 } },
      }),
    ),
    read(
      client.GET("/api/v1/databases/{db}/new-slow", {
        params: { path: { db }, query: { window, limit: 10 } },
      }),
    ),
    read(
      client.GET("/api/v1/databases/{db}/timeline", {
        params: { path: { db }, query: { window, series: 6 } },
      }),
    ),
  ]);
  const active = timeline.points.some((p) => p.sampled && p.measuredTotalMs > 0);
  return (
    <div className="space-y-8">
      <ParamLinks
        label="Time window"
        param="window"
        current={window}
        href={(p) => routes.trends(db, p)}
        options={[
          { value: "24h", text: "24 h" },
          { value: "7d", text: "7 days" },
          { value: "30d", text: "30 days" },
        ]}
      />

      {active && (
        <section aria-labelledby="time" className="sheet space-y-2 px-5 pt-4 pb-2">
          <div className="flex flex-wrap items-baseline gap-x-6 gap-y-2">
            <h2 id="time" className="mr-auto text-lg font-semibold">
              Measured time per hour
            </h2>
            <ChartLegend />
          </div>
          <TimeChart points={dbPoints(timeline.points)} />
        </section>
      )}

      {timeline.series.length > 0 && (
        <section aria-labelledby="busiest" className="space-y-2">
          <h2 id="busiest" className="text-lg font-semibold">
            The busiest queries, hour by hour
          </h2>
          <p className="text-muted-foreground text-sm">
            Each query&apos;s measured time per hour over the last {window}, drawn to its own scale.
          </p>
          <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {timeline.series.map((q, i) => (
              <li key={q.queryid} className="sheet space-y-2 p-4">
                <Link
                  href={routes.query(db, q.queryid)}
                  className="line-clamp-2 min-h-10 text-[13px] leading-5 underline-offset-4 hover:underline"
                >
                  {q.sqlPreview ? <SqlInline sql={q.sqlPreview} /> : `Query ${q.queryid}`}
                </Link>
                <Sparkline
                  values={timeline.points.map((p) =>
                    p.sampled ? (p.measuredMsBySeries[i] ?? 0) : null,
                  )}
                  label={`Measured time per hour of query ${q.queryid}`}
                />
                <p className="text-sm">
                  <span className="text-metric-total font-medium">
                    <Measured ms={q.measuredTotalMs} what="Total execution time in this window" />
                  </span>{" "}
                  <span className="text-muted-foreground">in total</span>
                  {q.estimatedMsSaved !== null && (
                    <>
                      <span className="text-muted-foreground"> · </span>
                      <Estimate
                        value={q.estimatedMsSaved}
                        unit="ms"
                        what="This window's measured time scaled by the planner's cost drop for its best index — not a measured speedup"
                      />
                      <span className="text-muted-foreground"> saved</span>
                    </>
                  )}
                </p>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-labelledby="movers" className="space-y-2">
        <h2 id="movers" className="text-lg font-semibold">
          Top movers
        </h2>
        <p className="text-muted-foreground text-sm">
          Measured total time in the last {window} ({formatUtc(movers.from)} onward) against the{" "}
          {window} before it, biggest change first.
        </p>
        {movers.items.length === 0 ? (
          <p className="sheet text-muted-foreground p-4 text-sm">
            No query activity to compare yet.
          </p>
        ) : (
          <div className="sheet overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-muted/60">
                <tr className="text-left">
                  <th scope="col" className="px-4 py-2.5 font-medium">
                    Query
                  </th>
                  <th scope="col" className="px-2 py-2.5 text-right font-medium">
                    Before
                  </th>
                  <th scope="col" className="px-2 py-2.5 text-right font-medium">
                    Now
                  </th>
                  <th scope="col" className="py-2.5 pr-4 pl-2 text-right font-medium">
                    Change
                  </th>
                </tr>
              </thead>
              <tbody>
                {movers.items.map((m) => (
                  <tr key={m.queryid} className="border-t align-top">
                    <td className="max-w-xl min-w-64 px-4 py-3">
                      <Link
                        href={routes.query(db, m.queryid, { window })}
                        className="line-clamp-2 text-[13px] leading-5 underline-offset-4 hover:underline"
                      >
                        <SqlInline sql={m.sql} />
                      </Link>
                    </td>
                    <td className={cn("px-2 py-3 text-right", METRIC_INK.total)}>
                      <Measured
                        ms={m.measuredPriorTotalMs}
                        what="Total time in the earlier window"
                      />
                    </td>
                    <td className={cn("px-2 py-3 text-right", METRIC_INK.total)}>
                      <Measured ms={m.measuredRecentTotalMs} what="Total time in this window" />
                    </td>
                    <td
                      className={cn(
                        "py-3 pr-4 pl-2 text-right font-medium whitespace-nowrap tabular-nums",
                        m.deltaMs > 0 ? "text-finding-foreground" : "text-success",
                      )}
                    >
                      {m.deltaMs > 0 ? (
                        <ArrowUpRightIcon aria-hidden className="mr-1 inline size-4 align-[-3px]" />
                      ) : (
                        <ArrowDownRightIcon
                          aria-hidden
                          className="mr-1 inline size-4 align-[-3px]"
                        />
                      )}
                      {m.deltaMs >= 0 ? "+" : "−"}
                      {formatMs(Math.abs(m.deltaMs))}
                      <span className="text-muted-foreground ml-1 text-xs">
                        {m.pctChange === null
                          ? "(new)"
                          : `(${m.pctChange >= 0 ? "+" : "−"}${formatPercent(Math.abs(m.pctChange))})`}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section aria-labelledby="new-slow" className="space-y-2">
        <h2 id="new-slow" className="text-lg font-semibold">
          New slow queries
        </h2>
        <p className="text-muted-foreground text-sm">
          First seen in the last {window} and already over {formatMs(fresh.minTotalMs)} of measured
          time.
        </p>
        {fresh.items.length === 0 ? (
          <p className="sheet text-muted-foreground p-4 text-sm">
            No new query crossed that line in this window.
          </p>
        ) : (
          <ul className="sheet divide-y">
            {fresh.items.map((n) => (
              <li key={n.queryid} className="px-4 py-3 text-sm">
                <Link
                  href={routes.query(db, n.queryid, { window })}
                  className="line-clamp-2 text-[13px] leading-5 underline-offset-4 hover:underline"
                >
                  <SqlInline sql={n.sql} />
                </Link>
                <p className="text-muted-foreground mt-1">
                  first seen {formatUtc(n.firstSeen)} ·{" "}
                  <Measured ms={n.measuredTotalMs} what="Total time since first seen" /> total ·{" "}
                  <Measured ms={n.measuredMeanMs} what="Mean time per call" /> mean ·{" "}
                  <Count value={n.calls} what="Calls" /> calls
                </p>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
