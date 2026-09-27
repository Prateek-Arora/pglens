import type { Metadata } from "next";
import { redirect } from "next/navigation";

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { api } from "@/lib/api/server";
import { safeNext } from "@/lib/paths";
import { Wordmark } from "@/components/wordmark";
import { LoginForm } from "./login-form";

export const metadata: Metadata = { title: "Sign in" };

export default async function LoginPage({ searchParams }: PageProps<"/login">) {
  const next = safeNext((await searchParams).next);
  // Already signed in, or the server runs without logins: nothing to do here. Asked directly (not
  // through `read`), because a 401 there would send us straight back to this page.
  const me = await (await api()).GET("/api/v1/me").catch(() => undefined);
  if (me?.response.ok) redirect(next);

  return (
    <main className="flex min-h-svh flex-col items-center justify-center gap-6 p-6">
      <Wordmark />
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>
            <h1>Sign in to PgLens</h1>
          </CardTitle>
          <CardDescription>Use the account your PgLens admin gave you.</CardDescription>
        </CardHeader>
        <CardContent>
          <LoginForm next={next} />
          {me === undefined && (
            <p role="alert" className="text-destructive mt-4 text-sm">
              Can&apos;t reach the PgLens server. Is it running?
            </p>
          )}
        </CardContent>
      </Card>
    </main>
  );
}
