import type { Metadata } from "next";

import { ConfirmBox } from "@/components/confirm-box";
import { IndexRecommendationCard } from "@/components/recommendations";
import { api, read } from "@/lib/api/server";

export const metadata: Metadata = { title: "Recommendations" };

export default async function RecommendationsPage() {
  const all = await read(
    (await api()).GET("/api/v1/recommendations", { params: { query: { limit: 50 } } }),
  );
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">What to fix first</h1>
      <p className="text-muted-foreground text-sm">
        Planner-validated indexes across every database, ranked by estimated time saved: each
        query&apos;s measured time scaled by the planner&apos;s cost drop. The ranking is an
        estimate; measure an index on a copy before you build it.
      </p>
      {all.recommended.length === 0 ? (
        <p className="text-muted-foreground text-sm">No recommendations yet.</p>
      ) : (
        <>
          <ConfirmBox confirm={all.confirm} />
          {all.recommended.map((r) => (
            <IndexRecommendationCard key={`${r.database}:${r.ddl}`} rec={r} showDatabase />
          ))}
        </>
      )}
    </div>
  );
}
