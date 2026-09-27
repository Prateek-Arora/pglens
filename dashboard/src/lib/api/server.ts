import "server-only";

import createClient from "openapi-fetch";
import { headers } from "next/headers";
import { notFound, redirect } from "next/navigation";
import { cache } from "react";

import { sessionToken } from "@/lib/session";
import { safeNext } from "@/lib/paths";
import type { paths } from "./schema";

/**
 * The data-access layer: the only code that calls the PgLens API (ADR-0046). It runs on the
 * Next.js server only (`server-only` fails the build if a client component imports it), attaches
 * the session token from the httpOnly cookie, and turns the API's answers into page outcomes:
 * 401 → the login page, 404 → the not-found page, anything else unexpected → the error boundary.
 * The API is the authority on who may see what; nothing here caches across requests or users.
 */
const baseUrl = process.env.PGLENS_API_URL ?? "http://localhost:8080";

/** An RFC 9457 problem detail, as the API sends for every error. */
export type Problem = { status?: number; title?: string; detail?: string };

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem | undefined,
  ) {
    super(problem?.detail ?? problem?.title ?? `PgLens API answered ${status}`);
    this.name = "ApiError";
  }
}

/** The API couldn't be reached at all (down, wrong PGLENS_API_URL). */
export class ApiUnavailableError extends Error {
  constructor(cause: unknown) {
    super(`Can't reach the PgLens server at ${baseUrl}`, { cause });
    this.name = "ApiUnavailableError";
  }
}

/** A typed API client for this request, carrying the caller's session (if any). */
export async function api() {
  return client(await sessionToken());
}

/** A client with no session, for logging in: a stale session must not ride along. */
export function anonymousApi() {
  return client(undefined);
}

function client(token: string | undefined) {
  return createClient<paths>({
    baseUrl,
    cache: "no-store",
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    fetch: async (request) => {
      try {
        return await fetch(request);
      } catch (e) {
        throw new ApiUnavailableError(e);
      }
    },
  });
}

type Result<T> = { data?: T; error?: unknown; response: Response };

/**
 * The response body of a successful call; otherwise leaves the page as described above. For reads
 * from Server Components; forms use {@link attempt} to show the API's message instead.
 */
export async function read<T>(call: Promise<Result<T>>): Promise<T> {
  const { data, error, response } = await call;
  if (response.ok) return data as T;
  if (response.status === 401) await toLogin();
  if (response.status === 404) notFound();
  throw new ApiError(response.status, error as Problem | undefined);
}

/**
 * For Server Actions: `{ ok: true, data }`, or `{ ok: false, message }` with the API's own words
 * (a name already taken, a wrong password). A lapsed session still goes to the login page.
 */
export async function attempt<T>(
  call: Promise<Result<T>>,
): Promise<{ ok: true; data: T } | { ok: false; status: number; message: string }> {
  const { data, error, response } = await call;
  if (response.ok) return { ok: true, data: data as T };
  if (response.status === 401) await toLogin();
  const problem = error as Problem | undefined;
  return {
    ok: false,
    status: response.status,
    message: problem?.detail ?? problem?.title ?? `The server answered ${response.status}.`,
  };
}

/**
 * Who is signed in (memoised per request). When the server runs with `pglens.auth.mode=none` it
 * answers for everyone with `via: "AUTH_DISABLED"` and `authRequired: false`.
 */
export const currentUser = cache(async () => read((await api()).GET("/api/v1/me")));

async function toLogin(): Promise<never> {
  // Set by src/proxy.ts; validated again here and by the login page.
  const here = (await headers()).get("x-pglens-path");
  redirect(`/login?next=${encodeURIComponent(safeNext(here))}`);
}
