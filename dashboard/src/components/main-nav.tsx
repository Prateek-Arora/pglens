"use client";

import Link from "next/link";
import type { Route } from "next";
import { usePathname } from "next/navigation";

import { cn } from "@/lib/utils";

const LINKS: { href: Route; text: string; current: (path: string) => boolean }[] = [
  { href: "/", text: "Databases", current: (p) => p === "/" || p.startsWith("/db/") },
  {
    href: "/recommendations",
    text: "Recommendations",
    current: (p) => p.startsWith("/recommendations"),
  },
  { href: "/settings", text: "Settings", current: (p) => p.startsWith("/settings") },
];

/** The main sections; on a phone they get their own scrollable row under the wordmark. */
export function MainNav({ className }: { className?: string }) {
  const path = usePathname();
  return (
    <nav aria-label="Main" className={className}>
      <ul className="-mx-2 flex overflow-x-auto text-sm">
        {LINKS.map((l) => {
          const current = l.current(path);
          return (
            <li key={l.href}>
              <Link
                href={l.href}
                aria-current={current ? "page" : undefined}
                className={cn(
                  "inline-block rounded-md px-2 py-2.5 whitespace-nowrap sm:py-1.5",
                  current
                    ? "text-foreground font-medium"
                    : "text-muted-foreground hover:text-foreground",
                )}
              >
                {l.text}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
