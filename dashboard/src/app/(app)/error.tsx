"use client";

import { Button } from "@/components/ui/button";

/**
 * A page failed to load. In production Next hides a server error's message (it could leak
 * details) and passes a digest instead, which matches the dashboard's server log.
 */
export default function PageError({
  error,
  retry,
}: {
  error: Error & { digest?: string };
  retry: () => void;
}) {
  return (
    <div role="alert" className="max-w-xl space-y-3 rounded-lg border p-6">
      <h1 className="text-lg font-semibold">This page couldn&apos;t load</h1>
      <p className="text-muted-foreground text-sm">
        The PgLens server may be down or restarting. Check that it&apos;s running (
        <code>docker compose ps</code>), then try again.
      </p>
      {error.digest && (
        <p className="text-muted-foreground text-xs">
          Reference for the dashboard&apos;s log: <code>{error.digest}</code>
        </p>
      )}
      <Button onClick={() => retry()}>Try again</Button>
    </div>
  );
}
