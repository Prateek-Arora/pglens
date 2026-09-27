import type { Metadata } from "next";
import type { ReactNode } from "react";

import { ConfirmBox } from "@/components/confirm-box";
import { ExplanationBlock } from "@/components/explanation";
import { Count, Estimate, Measured } from "@/components/numbers";
import { PlanTree } from "@/components/plan-tree";
import { RankStepper } from "@/components/rank-stepper";
import {
  CostDrop,
  Details,
  QueryRecommendationCard,
  StatusBadge,
} from "@/components/recommendations";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { METRIC_INK } from "@/components/scale-bar";
import { SqlBlock, SqlInline } from "@/components/sql";
import { withGaps } from "@/components/trend/series";
import { TrendChart } from "@/components/trend/trend-chart";
import { ParamLinks, parseWindow, windowStart } from "@/components/window-links";
import type { components } from "@/lib/api/schema";
import { api, read } from "@/lib/api/server";
import { formatMs, formatUtc } from "@/lib/format";
import { routes } from "@/lib/routes";
import { cn } from "@/lib/utils";

export const metadata: Metadata = { title: "Query" };

const SORTS = ["total", "mean", "calls"] as const;

export default async function QueryPage({
  params,
  searchParams,
}: PageProps<"/db/[db]/queries/[queryid]">) {
  const { db, queryid } = await params;
  const sp = await searchParams;
  const window = parseWindow(sp.window);
  const sort = SORTS.find((s) => s === sp.sort) ?? "total";
  const client = await api();
  const path = { db, queryid };
  const from = windowStart(window); // a Server Component renders once per request
  const [q, trend, explained, board] = await Promise.all([
    read(
      client.GET("/api/v1/databases/{db}/queries/{queryid}", {
        params: { path, query: { window } },
      }),
    ),
    read(
      client.GET("/api/v1/databases/{db}/queries/{queryid}/trend", {
        params: { path, query: { from, resolution: "auto" } },
      }),
    ),
    read(client.GET("/api/v1/databases/{db}/queries/{queryid}/explanation", { params: { path } })),
    // The ranking this page was opened from, for previous / next.
    read(
      client.GET("/api/v1/databases/{db}/queries", {
        params: { path: { db }, query: { window, sort, limit: 200, offset: 0 } },
      }),
    ),
  ]);
  const at = board.items.findIndex((i) => i.queryid === queryid);
  const keep = { window, sort: sort === "total" ? undefined : sort };
  const neighbour = (i: number) => {
    const n = board.items[i];
    return n ? routes.query(db, n.queryid, keep) : undefined;
  };
  const points = withGaps(trend.points);
  const validated = q.recommendations.some((r) => r.status === "PLANNER_VALIDATED");
  // The suggested index leads the page; every other recommendation follows further down.
  const top = best(q.recommendations);
  const others = q.recommendations.filter((r) => r !== top);
  const unplaced = q.findings.filter((f) => f.planNode === null || !q.plan);

  return (
    <div className="space-y-8">
      <header className="flex flex-wrap items-start gap-x-6 gap-y-3">
        <div className="min-w-0 flex-1 basis-80 space-y-1.5">
          <h2 className="line-clamp-2 text-[15px] leading-6 font-medium">
            <SqlInline sql={oneLine(q.sql)} />
          </h2>
          <p className="text-muted-foreground text-xs">
            <span className="font-mono">queryid {q.queryid}</span> · first seen{" "}
            {formatUtc(q.firstSeen)} · last seen {formatUtc(q.lastSeen)}
          </p>
        </div>
        {at >= 0 && (
          <RankStepper
            rank={at + 1}
            total={board.items.length}
            prev={neighbour(at - 1)}
            next={neighbour(at + 1)}
          />
        )}
      </header>

      <Verdict
        top={top}
        findings={q.findings.length}
        planMissing={q.plan ? null : (q.planUnavailableReason ?? "no reason was recorded")}
      />
      {validated && (
        <div id="measure" className="scroll-mt-4">
          <ConfirmBox confirm={q.confirm} />
        </div>
      )}

      <section aria-labelledby="plan" id="plan-section" className="scroll-mt-4 space-y-2">
        <h3 id="plan" className="text-base font-semibold">
          Plan
        </h3>
        {q.plan ? (
          <div className="sheet overflow-x-auto p-4">
            <PlanTree plan={q.plan} findings={q.findings} />
          </div>
        ) : (
          <p className="text-muted-foreground text-sm">
            No plan captured for this query: {q.planUnavailableReason ?? "no reason was recorded"}.
          </p>
        )}
        {unplaced.length > 0 && (
          <ul className="list-disc pl-5 text-sm">
            {unplaced.map((f) => (
              <li key={f.ruleId}>
                <strong>{f.title}.</strong> {f.evidence}
              </li>
            ))}
          </ul>
        )}
      </section>

      {explained.explanations.length > 0 && (
        <section aria-labelledby="explain" className="space-y-4">
          <h3 id="explain" className="text-base font-semibold">
            In plain language
          </h3>
          {explained.explanations.map((e) => (
            <ExplanationBlock key={e.ddl} e={e} />
          ))}
        </section>
      )}

      <section aria-labelledby="totals" className="space-y-3">
        <div className="flex flex-wrap items-center gap-3">
          <h3 id="totals" className="text-base font-semibold">
            Measured totals
          </h3>
          <ParamLinks
            label="Time window"
            param="window"
            current={window}
            href={(p) => routes.query(db, queryid, p)}
            options={[
              { value: "24h", text: "24 h" },
              { value: "7d", text: "7 days" },
              { value: "30d", text: "30 days" },
            ]}
          />
        </div>
        <TotalsTable window={window} inWindow={q.inWindow} sinceReset={q.sinceStatsReset} />
      </section>

      <section aria-labelledby="sql" className="space-y-2">
        <h3 id="sql" className="text-base font-semibold">
          SQL
        </h3>
        <SqlBlock sql={q.sql} copy label="the query" />
        {q.sqlTruncated && (
          <p className="text-muted-foreground text-xs">
            Postgres stored only the start of this query&apos;s text (
            <code>track_activity_query_size</code>).
          </p>
        )}
      </section>

      {others.length > 0 && (
        <section aria-labelledby="recs" id="recommendations" className="scroll-mt-4 space-y-3">
          <h3 id="recs" className="text-base font-semibold">
            Other index ideas
          </h3>
          {others.map((r) => (
            <QueryRecommendationCard key={r.ddl} rec={r} />
          ))}
        </section>
      )}

      <section aria-labelledby="trend" className="space-y-2">
        <h3 id="trend" className="text-base font-semibold">
          Mean time per call, measured
        </h3>
        {points.length === 0 ? (
          <p className="text-muted-foreground text-sm">No calls in this window.</p>
        ) : (
          <>
            <div className="sheet p-3">
              <TrendChart points={points} height={180} />
            </div>
            <details className="text-sm">
              <summary className="cursor-pointer">The numbers behind the chart</summary>
              <TrendTable points={trend.points} />
            </details>
          </>
        )}
      </section>
    </div>
  );
}

/** The query's SQL on one line, for a heading; the full, formatted text is in the SQL section. */
function oneLine(sql: string): string {
  const flat = sql.replace(/\s+/g, " ").trim();
  return flat.length > 160 ? `${flat.slice(0, 160)}…` : flat;
}

type Rec = components["schemas"]["QueryRecommendation"];

/** The index most worth building (planner-validated first, then by estimated time saved). */
function best(recs: Rec[]): Rec | undefined {
  const rank = (r: Rec) => (r.status === "PLANNER_VALIDATED" ? 0 : 1);
  return [...recs]
    .filter((r) => r.status !== "SUPPRESSED")
    .sort(
      (a, b) => rank(a) - rank(b) || (b.estimatedMsSaved ?? -1) - (a.estimatedMsSaved ?? -1),
    )[0];
}

/**
 * The answer first: the index to consider, what the planner estimates it saves, and why. The
 * `pglens confirm` step and the redlined plan that explains it follow directly below.
 */
function Verdict({
  top,
  findings,
  planMissing,
}: {
  top: Rec | undefined;
  findings: number;
  planMissing: string | null;
}) {
  if (!top) {
    return (
      <section
        aria-labelledby="verdict"
        className="bg-card rounded-lg border border-dashed p-5 text-sm"
      >
        <h3 id="verdict" className="text-base font-semibold">
          No index to suggest
        </h3>
        <p className="text-muted-foreground mt-1">
          {findings > 0 ? (
            <>
              PgLens found {findings} problem{findings === 1 ? "" : "s"} in the plan, but no index
              that fixes them.{" "}
              <a href="#plan-section" className="underline underline-offset-4">
                See the plan
              </a>
              .
            </>
          ) : planMissing ? (
            <>PgLens couldn&apos;t get this query&apos;s plan, so it had nothing to check.</>
          ) : (
            <>
              None of PgLens&apos;s rules matched this plan. They look for filtered full-table
              scans, unindexed join keys, a sort feeding a <code>LIMIT</code>, and jsonb or
              full-text filters. PgLens doesn&apos;t suggest expression indexes (such as on{" "}
              <code>lower(email)</code>) or partial indexes yet, so one may still help.
            </>
          )}
        </p>
      </section>
    );
  }
  const validated = top.status === "PLANNER_VALIDATED";
  return (
    <section
      aria-labelledby="verdict"
      className={
        validated
          ? "sheet border-success/40 space-y-4 p-5"
          : "bg-card space-y-4 rounded-lg border border-dashed p-5"
      }
    >
      <div className="flex flex-wrap items-center gap-2">
        <h3 id="verdict" className="text-base font-semibold">
          {validated ? "Suggested index" : "Index idea"}
        </h3>
        <StatusBadge status={top.status} />
      </div>
      <SqlBlock sql={top.ddl} pretty={false} copy label="the CREATE INDEX statement" />
      <div className="flex flex-wrap items-baseline gap-x-6 gap-y-2 text-sm">
        {top.estimatedMsSaved !== null && (
          <span>
            <span className="text-xl font-semibold">
              <Estimate
                value={top.estimatedMsSaved}
                unit="ms"
                what="Estimated time saved: the query's measured time scaled by the planner's cost drop. PgLens ranks by it; it is not a measured speedup"
              />
            </span>{" "}
            <span className="text-muted-foreground">saved</span>
          </span>
        )}
        {top.plannerCostDropFraction !== null && (
          <span>
            <span className="text-xl font-semibold">
              <Estimate
                value={top.plannerCostDropFraction}
                unit="drop"
                what="How much the planner's cost estimate drops with the index"
              />
            </span>{" "}
            <span className="text-muted-foreground">planner cost</span>
          </span>
        )}
        <a
          href={validated ? "#measure" : "#plan-section"}
          className="text-primary font-medium underline-offset-4 hover:underline"
        >
          {validated ? "How to measure it before you build it" : "See the plan it is for"}
        </a>
      </div>
      {top.plannerCostBefore !== null && top.plannerCostAfter !== null && (
        <CostDrop before={top.plannerCostBefore} after={top.plannerCostAfter} />
      )}
      {top.reason && <p className="text-muted-foreground text-sm">{top.reason}</p>}
      {top.buildCaution && (
        <Alert>
          <AlertDescription>{top.buildCaution}</AlertDescription>
        </Alert>
      )}
      {(top.rangeLabel || top.footprintLabel) && (
        <Details summary="Value ranges and index size">
          {top.rangeLabel && <p>{top.rangeLabel}</p>}
          {top.footprintLabel && <p>{top.footprintLabel}</p>}
        </Details>
      )}
    </section>
  );
}

type Totals = components["schemas"]["MeasuredTotals"];

/** The query's measured totals as one ruled table: metrics down, time spans across. */
function TotalsTable({
  window,
  inWindow,
  sinceReset,
}: {
  window: string;
  inWindow: Totals;
  sinceReset: Totals | null;
}) {
  const cols = [
    { title: `Last ${window}`, t: inWindow },
    ...(sinceReset ? [{ title: "Since pg_stat_statements was reset", t: sinceReset }] : []),
  ];
  const rows: { label: string; ink?: string; cell: (t: Totals) => ReactNode }[] = [
    {
      label: "Total time",
      ink: METRIC_INK.total,
      cell: (t) => <Measured ms={t.measuredTotalMs} what="Total execution time" />,
    },
    {
      label: "Mean time",
      ink: METRIC_INK.mean,
      cell: (t) => <Measured ms={t.measuredMeanMs} what="Mean execution time per call" />,
    },
    {
      label: "Calls",
      ink: METRIC_INK.calls,
      cell: (t) => <Count value={t.calls} what="Calls" />,
    },
    { label: "Rows", cell: (t) => <Count value={t.rows} what="Rows returned or affected" /> },
  ];
  return (
    <div className="sheet max-w-2xl overflow-x-auto">
      <table className="w-full text-sm">
        <thead className="bg-muted/60">
          <tr>
            <td />
            {cols.map((c) => (
              <th key={c.title} scope="col" className="px-4 py-2.5 text-right font-medium">
                {c.title}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.label} className="border-t">
              <th scope="row" className="text-muted-foreground px-4 py-2.5 text-left font-normal">
                {r.label}
              </th>
              {cols.map((c) => (
                <td
                  key={c.title}
                  className={cn("px-4 py-2.5 text-right text-base font-semibold", r.ink)}
                >
                  {r.cell(c.t)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function TrendTable({ points }: { points: components["schemas"]["TrendPointView"][] }) {
  return (
    <table className="mt-2 w-full text-xs">
      <thead>
        <tr className="text-muted-foreground text-left">
          <th scope="col">Sample (UTC)</th>
          <th scope="col" className="text-right">
            Calls
          </th>
          <th scope="col" className="text-right">
            Total time (measured)
          </th>
          <th scope="col" className="text-right">
            Mean time (measured)
          </th>
        </tr>
      </thead>
      <tbody>
        {points.map((p) => (
          <tr key={p.capturedAt} className="border-t">
            <td>{formatUtc(p.capturedAt)}</td>
            <td className="text-right">{p.calls}</td>
            <td className="text-right">{formatMs(p.measuredTotalMs)}</td>
            <td className="text-right">
              {p.measuredMeanMs === null ? "—" : formatMs(p.measuredMeanMs)}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
