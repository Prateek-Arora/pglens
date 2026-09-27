import Link from "next/link";

import { MainNav } from "@/components/main-nav";
import { Button } from "@/components/ui/button";
import { Wordmark } from "@/components/wordmark";
import { currentUser } from "@/lib/api/server";
import { logout } from "../login/actions";

/**
 * The signed-in shell. It reads the current user for display only (name, the no-login banner): layouts don't re-render on navigation, so they are never the access check —
 * every page's own API calls are (ADR-0046).
 */
export default async function AppLayout({ children }: LayoutProps<"/">) {
  const me = await currentUser();
  return (
    <>
      {!me.authRequired && (
        <div
          role="status"
          className="bg-amber-100 px-4 py-2 text-center text-sm text-amber-950 dark:bg-amber-950 dark:text-amber-100"
        >
          Logins are turned off (<code>pglens.auth.mode=none</code>): anyone who can reach this page
          has full admin access. Only use this on your own machine.
        </div>
      )}
      <a
        href="#main"
        className="bg-background sr-only z-10 rounded-md border px-3 py-2 text-sm focus:not-sr-only focus:absolute focus:top-2 focus:left-2"
      >
        Skip to content
      </a>
      <header className="bg-card border-b">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-6 px-4 pt-2 sm:py-2.5">
          <Link href="/" className="rounded-md py-1.5">
            <Wordmark />
          </Link>
          <MainNav className="order-last w-full sm:order-none sm:w-auto" />
          <div className="ml-auto flex items-center gap-3 text-sm">
            <span className="text-muted-foreground">
              {me.username}
              <span className="sr-only"> ({me.role.toLowerCase()})</span>
            </span>
            {me.authRequired && (
              <form action={logout}>
                <Button type="submit" variant="outline" size="sm">
                  Sign out
                </Button>
              </form>
            )}
          </div>
        </div>
      </header>
      <main
        id="main"
        tabIndex={-1}
        className="mx-auto w-full max-w-7xl flex-1 px-4 py-6 outline-none"
      >
        {children}
      </main>
    </>
  );
}
