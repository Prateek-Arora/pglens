import type { Metadata } from "next";

import { ConfirmBox } from "@/components/confirm-box";
import { IndexRecommendationCard, StatusBadge } from "@/components/recommendations";
import { Badge } from "@/components/ui/badge";
import { SqlInline } from "@/components/sql";
import { api, read } from "@/lib/api/server";

export async function generateMetadata({
  params,
}: PageProps<"/db/[db]/recommendations">): Promise<Metadata> {
  return { title: `Recommendations · ${(await params).db}` };
}

export default async function DatabaseRecommendationsPage({
  params,
}: PageProps<"/db/[db]/recommendations">) {
  const { db } = await params;
  const client = await api();
  const [recs, hygiene] = await Promise.all([
    read(client.GET("/api/v1/databases/{db}/recommendations", { params: { path: { db } } })),
    read(client.GET("/api/v1/databases/{db}/hygiene", { params: { path: { db } } })),
  ]);
  return (
    <div className="space-y-8">
      <section aria-labelledby="recommended" className="space-y-3">
        <h2 id="recommended" className="text-lg font-semibold">
          Indexes to consider, biggest estimated saving first
        </h2>
        {recs.recommended.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            No planner-validated index recommendation yet. PgLens checks candidates as it captures
            plans; this fills in as the agent reports.
          </p>
        ) : (
          <>
            <ConfirmBox confirm={recs.confirm} />
            {recs.recommended.map((r) => (
              <IndexRecommendationCard key={r.ddl} rec={r} />
            ))}
          </>
        )}
      </section>

      {recs.notPlannerValidated.length > 0 && (
        <section aria-labelledby="unvalidated" className="space-y-3">
          <h2 id="unvalidated" className="text-lg font-semibold">
            Not planner-validated
          </h2>
          <p className="text-muted-foreground text-sm">
            The planner never checked these. Each says why: HypoPG can&apos;t simulate some index
            types (GIN, GiST), and it can&apos;t check any index when it isn&apos;t installed or
            rejects the statement. They may well help; measure them on a copy before trusting them.
          </p>
          <ul className="sheet divide-y">
            {recs.notPlannerValidated.map((n) => (
              <li key={n.ddl} className="space-y-1.5 px-4 py-3 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <StatusBadge status="NOT_PLANNER_VALIDATED" />
                  <SqlInline sql={n.ddl} className="text-[13px]" />
                </div>
                {n.reason && <p className="text-muted-foreground">{n.reason}</p>}
                <p className="text-muted-foreground text-xs">
                  For {n.queryids.length} {n.queryids.length === 1 ? "query" : "queries"}
                </p>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-labelledby="hygiene" className="space-y-3">
        <h2 id="hygiene" className="text-lg font-semibold">
          Index hygiene
        </h2>
        {hygiene.findings.length === 0 ? (
          <p className="sheet text-muted-foreground p-4 text-sm">
            No duplicate, redundant or unused indexes found.
          </p>
        ) : (
          <ul className="sheet divide-y">
            {hygiene.findings.map((f) => (
              <li key={f.indexName} className="space-y-1.5 px-4 py-3 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant="secondary">{f.kind.toLowerCase()}</Badge>
                  <code className="font-mono text-[13px] font-medium">{f.indexName}</code>
                  <span className="text-muted-foreground">on {f.table}</span>
                </div>
                <p>{f.reason}</p>
                <SqlInline sql={f.definition} className="block text-[13px]" />
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
