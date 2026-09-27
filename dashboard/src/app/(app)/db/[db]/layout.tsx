import { AgentState, LastSample } from "@/components/agent-status";
import { TabsNav } from "@/components/tabs-nav";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { api, read } from "@/lib/api/server";
import { routes } from "@/lib/routes";

export default async function DatabaseLayout({ children, params }: LayoutProps<"/db/[db]">) {
  const { db: name } = await params;
  const db = await read(
    (await api()).GET("/api/v1/databases/{name}", { params: { path: { name } } }),
  );
  return (
    <div className="space-y-5">
      {/* The drawing's title block: what this sheet is about, and how fresh its data is. */}
      <div className="sheet flex flex-wrap items-stretch">
        <h1 className="flex min-w-0 grow items-center px-5 py-3 text-2xl font-semibold tracking-tight wrap-anywhere">
          {db.name}
        </h1>
        <dl className="flex flex-wrap text-sm max-sm:w-full max-sm:border-t">
          <div className="flex flex-col justify-center gap-0.5 px-5 py-3 sm:border-l">
            <dt className="text-muted-foreground text-xs">Agent</dt>
            <dd>
              <AgentState db={db} />
            </dd>
          </div>
          <div className="flex flex-col justify-center gap-0.5 border-l px-5 py-3">
            <dt className="text-muted-foreground text-xs">Last sample</dt>
            <dd className="font-medium">
              <LastSample db={db} now={new Date()} />
            </dd>
          </div>
        </dl>
      </div>
      {db.agent === "STALE" && (
        <Alert variant="destructive">
          <AlertTitle>The agent has stopped reporting</AlertTitle>
          <AlertDescription>
            PgLens hasn&apos;t received a sample for three intervals, so the numbers below stop at
            the last sample. Check that the agent is running and can reach the server.
          </AlertDescription>
        </Alert>
      )}
      <TabsNav
        label={`${db.name} sections`}
        tabs={[
          { href: routes.database(db.name), text: "Slow queries" },
          { href: routes.trends(db.name), text: "Trends" },
          { href: routes.recommendations(db.name), text: "Recommendations" },
        ]}
      />
      {children}
    </div>
  );
}
