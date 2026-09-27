import { CheckIcon, CircleDashedIcon } from "lucide-react";
import Link from "next/link";
import type { ReactNode } from "react";

import type { components } from "@/lib/api/schema";
import { Estimate } from "@/components/numbers";
import { ScaleBar } from "@/components/scale-bar";
import { SqlBlock, SqlInline } from "@/components/sql";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatUtc } from "@/lib/format";
import { routes } from "@/lib/routes";

type QueryRecommendation = components["schemas"]["QueryRecommendation"];
type IndexRecommendation = components["schemas"]["IndexRecommendation"];

const SAVED =
  "Estimated time saved: the query's measured time scaled by the planner's cost drop. PgLens ranks by it; it is not a measured speedup";

export function StatusBadge({ status }: { status: string }) {
  switch (status) {
    case "PLANNER_VALIDATED":
      return (
        <Badge variant="success">
          <CheckIcon aria-hidden />
          Planner-validated
        </Badge>
      );
    case "NOT_PLANNER_VALIDATED":
      return (
        <Badge variant="outline" className="text-muted-foreground border-dashed">
          <CircleDashedIcon aria-hidden />
          Not planner-validated
        </Badge>
      );
    case "SUPPRESSED":
      return <Badge variant="secondary">Rejected by the planner check</Badge>;
    default:
      return <Badge variant="secondary">{status.toLowerCase().replaceAll("_", " ")}</Badge>;
  }
}

/**
 * The planner's cost now and with the index, drawn to scale. Both are estimates, so both bars are
 * hatched; the index's bar is in the action ink.
 */
export function CostDrop({ before, after }: { before: number; after: number }) {
  return (
    <div className="grid max-w-xl grid-cols-[auto_1fr_auto] items-center gap-x-3 gap-y-2 text-sm">
      <span className="text-muted-foreground">Planner cost now</span>
      <ScaleBar value={before} max={before} ink="text-muted-foreground" estimate className="h-3" />
      <span className="text-right">
        <Estimate value={before} unit="cost" what="Planner cost now" />
      </span>
      <span className="text-muted-foreground">With the index</span>
      <ScaleBar value={after} max={before} ink="text-primary" estimate className="h-3" />
      <span className="text-right">
        <Estimate value={after} unit="cost" what="Planner cost with the hypothetical index" />
      </span>
    </div>
  );
}

/** Supporting evidence that makes a card long: one click away instead of a wall of text. */
export function Details({ summary, children }: { summary: string; children: ReactNode }) {
  return (
    <details className="group text-sm">
      <summary className="text-muted-foreground hover:text-foreground cursor-pointer select-none">
        {summary}
      </summary>
      <div className="text-muted-foreground mt-2 space-y-2 leading-relaxed">{children}</div>
    </details>
  );
}

/** One index checked for one query (query detail page). */
export async function QueryRecommendationCard({ rec }: { rec: QueryRecommendation }) {
  const more = [rec.rangeLabel, rec.footprintLabel].filter((t): t is string => Boolean(t));
  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center gap-2">
        <StatusBadge status={rec.status} />
        <span className="text-muted-foreground text-sm">{rec.accessMethod.toLowerCase()}</span>
        <span className="text-muted-foreground ml-auto text-sm">
          checked {formatUtc(rec.validatedAt)}
        </span>
      </CardHeader>
      <CardContent className="space-y-4">
        <SqlBlock sql={rec.ddl} pretty={false} copy label="the CREATE INDEX statement" />
        {rec.plannerCostBefore !== null && rec.plannerCostAfter !== null && (
          <div className="space-y-4">
            <dl className="flex flex-wrap gap-x-8 gap-y-3 text-sm">
              <div>
                <dt className="text-muted-foreground">Estimated time saved</dt>
                <dd className="text-lg font-semibold">
                  <Estimate value={rec.estimatedMsSaved} unit="ms" what={SAVED} />
                </dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Cost drop</dt>
                <dd className="text-lg font-semibold">
                  <Estimate
                    value={rec.plannerCostDropFraction}
                    unit="drop"
                    what="How much the planner's cost estimate drops with the index"
                  />
                </dd>
              </div>
            </dl>
            <CostDrop before={rec.plannerCostBefore} after={rec.plannerCostAfter} />
          </div>
        )}
        {rec.reason && <p className="text-sm">{rec.reason}</p>}
        {rec.buildCaution && (
          <Alert>
            <AlertDescription>{rec.buildCaution}</AlertDescription>
          </Alert>
        )}
        {more.length > 0 && (
          <Details summary="Value ranges and index size">
            {more.map((t) => (
              <p key={t}>{t}</p>
            ))}
          </Details>
        )}
      </CardContent>
    </Card>
  );
}

/** One index, with every query it helps (recommendations pages). */
export async function IndexRecommendationCard({
  rec,
  showDatabase = false,
}: {
  rec: IndexRecommendation;
  showDatabase?: boolean;
}) {
  const ranges = rec.queries.filter((q) => q.rangeLabel);
  const hasDetails = rec.footprintLabel || rec.writeLoad || ranges.length > 0;
  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-baseline gap-x-3 gap-y-1">
        <CardTitle className="text-base font-semibold">
          {rec.table ?? "index"}
          {showDatabase && (
            <span className="text-muted-foreground font-normal"> · {rec.database}</span>
          )}
        </CardTitle>
        {!rec.actionable && <Badge variant="secondary">Covered by another index</Badge>}
        <span className="ml-auto text-sm">
          <span className="text-base font-semibold">
            <Estimate value={rec.estimatedMsSaved} unit="ms" what={SAVED} />
          </span>{" "}
          <span className="text-muted-foreground">saved</span>
        </span>
      </CardHeader>
      <CardContent className="space-y-4">
        <SqlBlock sql={rec.ddl} pretty={false} copy label="the CREATE INDEX statement" />
        {rec.redundantWith && (
          <p className="text-sm">
            A more general index, <code>{rec.redundantWith}</code>, also passed the planner check
            for these queries.
          </p>
        )}
        {rec.buildCaution && (
          <Alert>
            <AlertDescription>{rec.buildCaution}</AlertDescription>
          </Alert>
        )}
        <table className="w-full text-sm">
          <caption className="text-muted-foreground mb-2 text-left">
            Helps {rec.queries.length} {rec.queries.length === 1 ? "query" : "queries"}
          </caption>
          <thead>
            <tr className="text-muted-foreground text-left">
              <th scope="col" className="pb-1 font-normal">
                Query
              </th>
              <th scope="col" className="pb-1 pl-4 text-right font-normal">
                Cost drop
              </th>
              <th scope="col" className="pb-1 pl-4 text-right font-normal">
                Est. saved
              </th>
            </tr>
          </thead>
          <tbody>
            {rec.queries.map((q) => (
              <tr key={q.queryid} className="border-t align-top">
                <td className="py-2">
                  <Link
                    href={routes.query(rec.database, q.queryid)}
                    className="line-clamp-2 text-[13px] leading-5 underline-offset-4 hover:underline"
                  >
                    {q.sqlPreview ? <SqlInline sql={q.sqlPreview} /> : `Query ${q.queryid}`}
                  </Link>
                </td>
                <td className="py-2 pl-4 text-right">
                  <Estimate
                    value={q.plannerCostDropFraction}
                    unit="drop"
                    what="How much the planner's cost estimate drops with the index"
                  />
                </td>
                <td className="py-2 pl-4 text-right">
                  <Estimate value={q.estimatedMsSaved} unit="ms" what={SAVED} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {hasDetails && (
          <Details summary="Index size, write load and value ranges">
            {rec.footprintLabel && <p>{rec.footprintLabel}</p>}
            {rec.writeLoad && <p>{rec.writeLoad.label}</p>}
            {ranges.map((q) => (
              <p key={q.queryid}>
                <span className="text-[13px]">
                  {q.sqlPreview ? <SqlInline sql={q.sqlPreview} /> : `Query ${q.queryid}`}
                </span>
                <br />
                {q.rangeLabel}
              </p>
            ))}
          </Details>
        )}
      </CardContent>
    </Card>
  );
}
