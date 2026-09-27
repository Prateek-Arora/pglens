import type { components } from "@/lib/api/schema";
import { SqlInline } from "@/components/sql";
import { Badge } from "@/components/ui/badge";
import { formatUtc } from "@/lib/format";

type Explanation = components["schemas"]["ExplanationView"];

/**
 * A plain-language explanation. The template is written by PgLens from the stored facts on every
 * read, so one is always there, LLM or not; an LLM answer is shown only when one was cached for the
 * same facts, and says which model wrote it (ADR-0043).
 */
export async function ExplanationBlock({ e }: { e: Explanation }) {
  const llm = e.source === "LLM";
  return (
    <article className="sheet space-y-3 p-5 text-sm leading-relaxed">
      <div className="flex flex-wrap items-center gap-2">
        <SqlInline sql={e.ddl} className="min-w-0 text-[13px]" />
        <Badge variant="outline">
          {llm ? `Written by ${e.model ?? "an LLM"}` : "Written by PgLens from the facts"}
        </Badge>
      </div>
      {llm && (
        <p className="text-muted-foreground text-xs">
          Generated {e.generatedAt ? formatUtc(e.generatedAt) : ""} (prompt {e.promptVersion}),
          checked against PgLens&apos;s facts before it was kept. The numbers come from PgLens, not
          the model.
        </p>
      )}
      <p className="max-w-[75ch] text-[15px]">{e.summary}</p>
      <div className="grid gap-4 md:grid-cols-2">
        <div className="space-y-1">
          <h4 className="font-semibold">Why it is slow</h4>
          <p className="max-w-[75ch]">{e.whyItIsSlow}</p>
        </div>
        <div className="space-y-1">
          <h4 className="font-semibold">What the index changes</h4>
          <p className="max-w-[75ch]">{e.whatTheIndexChanges}</p>
        </div>
      </div>
      {e.docs.length > 0 && (
        <p className="text-muted-foreground">
          Read more:{" "}
          {e.docs.map((d, i) => (
            <span key={d}>
              {i > 0 && " · "}
              <a href={d} className="underline underline-offset-4" rel="noreferrer" target="_blank">
                {docLabel(d)}
              </a>
            </span>
          ))}
        </p>
      )}
    </article>
  );
}

/** "postgresql.org › using-explain.html" for a documentation link. */
function docLabel(url: string): string {
  const u = new URL(url);
  const page = u.pathname.replace(/\/$/, "").split("/").pop();
  const host = u.hostname.replace(/^www\./, "");
  return page ? `${host} › ${page}` : host;
}
