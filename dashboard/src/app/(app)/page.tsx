import type { Metadata } from "next";
import type { ReactNode } from "react";
import Link from "next/link";
import { ArrowRightIcon } from "lucide-react";

import { AgentState } from "@/components/agent-status";
import { AppliedIndexCard, ChartLegend, Kpi, overviewPoints } from "@/components/impact/impact";
import { TimeChart } from "@/components/impact/time-chart";
import { Count, Estimate, Measured } from "@/components/numbers";
import { SqlInline } from "@/components/sql";
import { ParamLinks, parseWindow } from "@/components/window-links";
import { api, currentUser, read } from "@/lib/api/server";
import { formatPercent, formatUtc } from "@/lib/format";
import { routes } from "@/lib/routes";
import { RegisterForm } from "./register-form";

export const metadata: Metadata = { title: "Overview" };

const SAVED =
  "The window's measured time of each query with a planner-validated index, scaled by the planner's cost drop for its best index. What the indexes would save if the planner is right — not a measured speedup";

const WINDOW_TEXT = { "24h": "last 24 hours", "7d": "last 7 days", "30d": "last 30 days" };

export default async function OverviewPage({ searchParams }: PageProps<"/">) {
  const window = parseWindow((await searchParams).window);
  const client = await api();
  const [overview, databases, me] = await Promise.all([
    read(client.GET("/api/v1/overview", { params: { query: { window } } })),
    read(client.GET("/api/v1/databases")),
    currentUser(),
  ]);
  const admin = me.role === "ADMIN";

  if (databases.length === 0) {
    return <FirstRun admin={admin} />;
  }

  const total = overview.measuredTotalMs;
  const share = total > 0 ? overview.measuredMsWithAdvice / total : 0;
  const applied = overview.databases.reduce((n, d) => n + d.appliedIndexes, 0);
  const hasTime = total > 0;

  return (
    <div className="space-y-8">
      <div className="flex flex-wrap items-end gap-3">
        <div className="mr-auto space-y-1">
          <h1 className="text-2xl font-semibold tracking-tight">Overview</h1>
          <p className="text-muted-foreground text-sm">
            {databases.length} {databases.length === 1 ? "database" : "databases"} ·{" "}
            {WINDOW_TEXT[window]} (UTC, from {formatUtc(overview.from).slice(11)})
          </p>
        </div>
        <ParamLinks
          label="Time window"
          param="window"
          current={window}
          href={(p) => routes.overview({ window: p.window === "24h" ? undefined : p.window })}
          options={[
            { value: "24h", text: "24 h" },
            { value: "7d", text: "7 days" },
            { value: "30d", text: "30 days" },
          ]}
        />
      </div>

      <dl className="sheet bg-border grid grid-cols-2 gap-px overflow-hidden lg:grid-cols-4">
        <Kpi
          label="Query time measured"
          note={
            <>
              <Count value={overview.calls} what="Calls in this window" /> calls across{" "}
              <Count value={overview.queries} what="Queries that ran in this window" /> queries
            </>
          }
        >
          <span className="text-metric-total">
            <Measured ms={total} what="Total execution time of every query in this window" />
          </span>
        </Kpi>
        <Kpi
          label="In queries with an index to try"
          note={hasTime ? `${formatPercent(share)} of the measured time` : "No query time yet"}
        >
          <Measured
            ms={overview.measuredMsWithAdvice}
            what="Measured time of the queries that have a planner-validated index to consider"
          />
        </Kpi>
        <Kpi
          label="Planner-estimated saving"
          note="If the indexes behave as the planner expects — measure on a copy first"
        >
          <Estimate value={overview.estimatedMsSaved} unit="ms" what={SAVED} />
        </Kpi>
        <Kpi
          label="Indexes to consider"
          note={
            <>
              {applied > 0 && (
                <>
                  <Count value={applied} what="Recommended indexes that were built" /> built and
                  measured ·{" "}
                </>
              )}
              {overview.notPlannerValidated > 0 ? (
                <>
                  <Count
                    value={overview.notPlannerValidated}
                    what="Indexes HypoPG couldn't check (GIN/GiST)"
                  />{" "}
                  not planner-validated
                </>
              ) : (
                "all planner-validated"
              )}
            </>
          }
        >
          <Count value={overview.indexesToConsider} what="Planner-validated indexes to consider" />
        </Kpi>
      </dl>

      <section aria-labelledby="time" className="sheet space-y-3 p-5">
        <div className="flex flex-wrap items-baseline gap-x-6 gap-y-2">
          <h2 id="time" className="mr-auto text-lg font-semibold">
            Where the time went
          </h2>
          <ChartLegend />
        </div>
        {hasTime ? (
          <TimeChart points={overviewPoints(overview.timeline)} />
        ) : (
          <p className="text-muted-foreground py-10 text-center text-sm">
            No query activity in this window yet. A query&apos;s first sample only anchors its
            counters, so time shows up from the agent&apos;s second sample on.
          </p>
        )}
      </section>

      <div className="grid gap-8 lg:grid-cols-2">
        <section aria-labelledby="fix" className="sheet flex flex-col p-5">
          <SectionHead id="fix" title="What to fix first" href="/recommendations" more="All" />
          {overview.topRecommendations.length === 0 ? (
            <p className="text-muted-foreground text-sm">
              No planner-validated index to suggest yet. PgLens checks each slow query&apos;s plan
              every 30 seconds once its agent has sent it.
            </p>
          ) : (
            <ol className="divide-y">
              {overview.topRecommendations.map((rec, i) => (
                <li key={`${rec.database}:${rec.ddl}`} className="flex gap-4 py-4 first:pt-1">
                  <span className="text-muted-foreground w-4 shrink-0 text-sm tabular-nums">
                    {i + 1}
                  </span>
                  <div className="min-w-0 flex-1 space-y-1.5">
                    <p className="text-[13px] leading-5 break-words">
                      <SqlInline sql={rec.ddl} />
                    </p>
                    <p className="text-muted-foreground text-sm">
                      <span className="text-foreground font-semibold">
                        <Estimate
                          value={rec.estimatedMsSaved}
                          unit="ms"
                          what="The query's measured time scaled by the planner's cost drop — PgLens ranks by it; not a measured speedup"
                        />
                      </span>{" "}
                      saved · helps {rec.queries.length}{" "}
                      {rec.queries.length === 1 ? "query" : "queries"} on{" "}
                      <Link
                        href={routes.recommendations(rec.database)}
                        className="underline underline-offset-4"
                      >
                        {rec.database}
                      </Link>
                    </p>
                  </div>
                </li>
              ))}
            </ol>
          )}
          <p className="text-muted-foreground mt-auto border-t pt-3 text-[13px]">
            Planner-validated ≠ safe: the planner <em>estimates</em> a cheaper plan. Time an index
            on a copy with <code className="font-mono">pglens confirm</code> before you build it.
          </p>
        </section>

        <section aria-labelledby="built" className="sheet space-y-4 p-5">
          <SectionHead id="built" title="Built and measured" />
          {overview.applied.length === 0 ? (
            <div className="text-muted-foreground space-y-2 text-sm">
              <p>
                When an index PgLens suggested appears on the database, PgLens notices it on the
                agent&apos;s next sample, retires the advice, and compares each query&apos;s{" "}
                <strong className="text-foreground font-medium">measured</strong> time per call
                before and after — here.
              </p>
              <p>Nothing built yet.</p>
            </div>
          ) : (
            <>
              <div className="divide-y [&>*]:py-4 [&>*:first-child]:pt-0">
                {overview.applied.slice(0, 3).map((a) => (
                  <AppliedIndexCard key={`${a.database}:${a.ddl}`} applied={a} showDatabase />
                ))}
              </div>
              <p className="text-muted-foreground text-[13px]">{overview.appliedCaveat}</p>
            </>
          )}
        </section>
      </div>

      <section aria-labelledby="dbs" className="space-y-3">
        <h2 id="dbs" className="text-lg font-semibold">
          Databases
        </h2>
        <ul className="sheet divide-y overflow-hidden">
          {overview.databases.map((db) => {
            const view = databases.find((d) => d.name === db.name);
            return (
              <li
                key={db.name}
                className="hover:bg-accent/60 relative grid gap-x-6 gap-y-2 px-4 py-3 transition-colors sm:grid-cols-[minmax(10rem,1fr)_auto_auto_auto]"
              >
                <div className="min-w-0">
                  <Link
                    href={routes.database(db.name, window === "24h" ? undefined : { window })}
                    className="font-semibold after:absolute after:inset-0 hover:underline"
                  >
                    {db.name}
                  </Link>
                  <div className="mt-0.5">{view && <AgentState db={view} />}</div>
                </div>
                <Stat label="Measured">
                  <span className="text-metric-total">
                    <Measured ms={db.measuredTotalMs} what="Total execution time in this window" />
                  </span>
                </Stat>
                <Stat label="Est. saving">
                  <Estimate value={db.estimatedMsSaved} unit="ms" what={SAVED} />
                </Stat>
                <Stat label="Indexes to consider">
                  <Count
                    value={db.indexesToConsider}
                    what="Planner-validated indexes to consider"
                  />
                </Stat>
              </li>
            );
          })}
        </ul>
        {admin && (
          <details className="sheet group p-5">
            <summary className="cursor-pointer font-semibold select-none">Add a database</summary>
            <div className="mt-4">
              <RegisterForm />
            </div>
          </details>
        )}
      </section>
    </div>
  );
}

function SectionHead({
  id,
  title,
  href,
  more,
}: {
  id: string;
  title: string;
  href?: "/recommendations";
  more?: string;
}) {
  return (
    <div className="mb-2 flex items-baseline gap-3">
      <h2 id={id} className="mr-auto text-lg font-semibold">
        {title}
      </h2>
      {href && (
        <Link
          href={href}
          className="text-primary inline-flex items-center gap-1 text-sm underline-offset-4 hover:underline"
        >
          {more} <ArrowRightIcon aria-hidden className="size-3.5" />
        </Link>
      )}
    </div>
  );
}

function Stat({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-baseline gap-2 text-sm sm:flex-col sm:items-end sm:gap-0.5">
      <span className="text-muted-foreground text-[13px]">{label}</span>
      <span className="font-medium">{children}</span>
    </div>
  );
}

/** No database yet: what PgLens does, and the three steps to see it on your own. */
function FirstRun({ admin }: { admin: boolean }) {
  return (
    <div className="mx-auto max-w-3xl space-y-8 py-4">
      <div className="space-y-3">
        <h1 className="text-2xl font-semibold tracking-tight">Point PgLens at a database</h1>
        <p className="text-muted-foreground max-w-[65ch]">
          PgLens reads <code className="font-mono text-[0.92em]">pg_stat_statements</code> through a
          read-only agent, shows where your query time goes, checks index ideas against your own
          planner with HypoPG, and — once you build one — measures what it really changed.
        </p>
      </div>
      <ol className="sheet divide-y">
        {[
          [
            "Prepare the database",
            "PostgreSQL 16+, pg_stat_statements loaded, hypopg if you can, and a read-only role (the README has the exact SQL).",
          ],
          ["Add it here", "PgLens shows the agent's token once, with a ready-to-fill config."],
          [
            "Start the agent next to it",
            "It only dials out to this server (gRPC, TLS). Data shows up from its second sample.",
          ],
        ].map(([title, text], i) => (
          <li key={title} className="flex gap-4 px-5 py-4">
            <span className="border-primary text-primary flex size-6 shrink-0 items-center justify-center rounded-full border text-sm font-medium">
              {i + 1}
            </span>
            <div>
              <p className="font-medium">{title}</p>
              <p className="text-muted-foreground text-sm">{text}</p>
            </div>
          </li>
        ))}
      </ol>
      {admin ? (
        <section aria-labelledby="add-db" className="sheet space-y-3 p-5">
          <h2 id="add-db" className="text-base font-semibold">
            Add a database
          </h2>
          <RegisterForm />
        </section>
      ) : (
        <p className="text-muted-foreground">Ask an admin to add one.</p>
      )}
      <p className="text-muted-foreground text-sm">
        Just trying it? <code className="font-mono">make register && make seed && make warmup</code>{" "}
        loads the bundled demo database.
      </p>
    </div>
  );
}
