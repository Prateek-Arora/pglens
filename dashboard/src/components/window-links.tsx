import Link from "next/link";
import type { Route } from "next";

import { cn } from "@/lib/utils";

export const WINDOWS = ["24h", "7d", "30d"] as const;
export type Window = (typeof WINDOWS)[number];

export function parseWindow(value: unknown, fallback: Window = "24h"): Window {
  return WINDOWS.find((w) => w === value) ?? fallback;
}

const SPAN_MS: Record<Window, number> = { "24h": 864e5, "7d": 7 * 864e5, "30d": 30 * 864e5 };

/** The instant a window of this length ending now starts, for APIs that take `from`. */
export function windowStart(window: Window, now: Date = new Date()): string {
  return new Date(now.getTime() - SPAN_MS[window]).toISOString();
}

/**
 * A set of links that each set one URL parameter (window, sort…): state lives in the URL, so a
 * view is shareable and rendered on the server, with no client JavaScript.
 */
export function ParamLinks<T extends string>({
  label,
  param,
  options,
  current,
  href,
}: {
  label: string;
  param: string;
  options: readonly { value: T; text: string; ink?: string }[];
  current: T;
  href: (params: Record<string, string>) => Route;
}) {
  return (
    <nav aria-label={label} className="bg-card inline-flex rounded-md border p-0.5 text-sm">
      {options.map((o) => (
        <Link
          key={o.value}
          href={href({ [param]: o.value })}
          aria-current={o.value === current ? "page" : undefined}
          className={cn(
            "inline-flex items-center gap-1.5 rounded-[calc(var(--radius)-2px)] px-3 py-2 whitespace-nowrap transition-colors sm:py-1",
            o.value === current
              ? "bg-foreground text-background font-medium"
              : "text-muted-foreground hover:text-foreground hover:bg-accent",
          )}
        >
          {o.ink && (
            <span
              aria-hidden
              className={cn("size-2 rounded-full bg-current", o.value !== current && o.ink)}
            />
          )}
          {o.text}
        </Link>
      ))}
    </nav>
  );
}
