import type { components } from "@/lib/api/schema";
import { formatAgo, formatUtc } from "@/lib/format";
import { cn } from "@/lib/utils";

type Database = components["schemas"]["Database"];

/**
 * The agent's state as the server sees it (stale = no sample for 3 intervals), drawn as a mark:
 * solid green = live, solid red = stale, a dashed ring = no data yet. The words always say it too.
 */
export function AgentState({ db }: { db: Database }) {
  const { text, mark } = {
    CONNECTED: { text: "Agent connected", mark: "bg-success ring-success/20 ring-4" },
    STALE: { text: "Agent stale", mark: "bg-destructive ring-destructive/20 ring-4" },
    NEVER_CONNECTED: {
      text: "Waiting for the agent",
      mark: "border-muted-foreground border border-dashed",
    },
  }[db.agent];
  return (
    <span className="inline-flex items-center gap-2 text-sm font-medium">
      <span aria-hidden className={cn("size-2 shrink-0 rounded-full", mark)} />
      {text}
    </span>
  );
}

/** When the last sample arrived, relative, with the exact UTC time on hover. */
export function LastSample({ db, now }: { db: Database; now: Date }) {
  const last = db.lastIngestAt;
  return last ? (
    <time dateTime={last} title={formatUtc(last)}>
      {formatAgo(last, now)}
    </time>
  ) : (
    <>no sample yet</>
  );
}

/** State and last sample on one line, for lists. */
export function AgentStatus({ db, now }: { db: Database; now: Date }) {
  return (
    <span className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
      <AgentState db={db} />
      <span className="text-muted-foreground">
        {db.lastIngestAt ? "last sample " : ""}
        <LastSample db={db} now={now} />
      </span>
    </span>
  );
}
