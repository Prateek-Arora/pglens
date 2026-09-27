"use client";

import { ChevronLeftIcon, ChevronRightIcon } from "lucide-react";
import Link from "next/link";
import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useEffect } from "react";

import { cn } from "@/lib/utils";

/**
 * Step through the leaderboard from a query's page: the previous and next query by the same
 * window and sort, also on the `k` and `j` keys (ignored while typing or with a modifier held).
 */
export function RankStepper({
  rank,
  total,
  prev,
  next,
}: {
  rank: number;
  total: number;
  prev?: Route;
  next?: Route;
}) {
  const router = useRouter();
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.metaKey || e.ctrlKey || e.altKey || e.defaultPrevented) return;
      const t = e.target as HTMLElement | null;
      if (t?.closest("input, textarea, select, [contenteditable=true]")) return;
      const to = e.key === "j" ? next : e.key === "k" ? prev : undefined;
      if (to) router.push(to);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [router, prev, next]);

  const step =
    "hover:bg-accent inline-flex size-9 items-center justify-center rounded-md border bg-card";
  return (
    <nav aria-label="Step through the ranking" className="flex shrink-0 items-center gap-2 text-sm">
      {prev ? (
        <Link
          href={prev}
          className={step}
          aria-label="Previous query (k)"
          title="Previous query (k)"
        >
          <ChevronLeftIcon aria-hidden className="size-4" />
        </Link>
      ) : (
        <span aria-hidden className={cn(step, "text-muted-foreground/50 cursor-default")}>
          <ChevronLeftIcon className="size-4" />
        </span>
      )}
      <span className="text-muted-foreground min-w-16 text-center tabular-nums">
        <span className="text-foreground font-semibold">#{rank}</span> of {total}
      </span>
      {next ? (
        <Link href={next} className={step} aria-label="Next query (j)" title="Next query (j)">
          <ChevronRightIcon aria-hidden className="size-4" />
        </Link>
      ) : (
        <span aria-hidden className={cn(step, "text-muted-foreground/50 cursor-default")}>
          <ChevronRightIcon className="size-4" />
        </span>
      )}
    </nav>
  );
}
