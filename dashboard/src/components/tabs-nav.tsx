"use client";

import Link from "next/link";
import type { Route } from "next";
import { usePathname } from "next/navigation";

import { cn } from "@/lib/utils";

/** Section links; the current one is marked for assistive tech as well as visually. */
export function TabsNav({ label, tabs }: { label: string; tabs: { href: Route; text: string }[] }) {
  const path = usePathname();
  return (
    <nav aria-label={label} className="border-b">
      <ul className="-mb-px flex gap-4 overflow-x-auto text-sm">
        {tabs.map((t, i) => {
          // The first tab is the section root: current only on an exact match.
          const current = i === 0 ? path === t.href : path.startsWith(t.href);
          return (
            <li key={t.href}>
              <Link
                href={t.href}
                aria-current={current ? "page" : undefined}
                className={cn(
                  "inline-block border-b-2 px-1 py-3 whitespace-nowrap sm:py-2",
                  current
                    ? "border-primary text-foreground font-medium"
                    : "text-muted-foreground hover:text-foreground border-transparent",
                )}
              >
                {t.text}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
