import "server-only";

import { cookies, headers } from "next/headers";

/**
 * The session cookie holds the API's opaque session token. It is httpOnly (page scripts can't read
 * it) and never leaves the Next.js server: the browser sends it here, and the data-access layer
 * forwards it to the API as a bearer token.
 *
 * Over HTTPS (behind the reverse proxy) it is `__Host-` prefixed and Secure: browsers then refuse
 * it unless it came over HTTPS, from this exact host, for path "/". Plain-HTTP installs (localhost)
 * can't have a Secure cookie, so they use the unprefixed name.
 */
const SECURE_NAME = "__Host-pglens_session";
const PLAIN_NAME = "pglens_session";

/** The API's absolute session limit (ADR-0044); the API decides validity, this only bounds the cookie. */
const MAX_AGE_SECONDS = 7 * 24 * 60 * 60;

export async function sessionToken(): Promise<string | undefined> {
  const jar = await cookies();
  return jar.get(SECURE_NAME)?.value ?? jar.get(PLAIN_NAME)?.value;
}

/** Only from a Server Action or Route Handler (cookies can't be set while rendering). */
export async function startSession(token: string): Promise<void> {
  const secure = await isHttps();
  const jar = await cookies();
  jar.set(secure ? SECURE_NAME : PLAIN_NAME, token, {
    httpOnly: true,
    secure,
    sameSite: "lax",
    path: "/",
    maxAge: MAX_AGE_SECONDS,
  });
}

/** Only from a Server Action or Route Handler. */
export async function endSession(): Promise<void> {
  const jar = await cookies();
  jar.delete({ name: SECURE_NAME, path: "/", secure: true });
  jar.delete(PLAIN_NAME);
}

/**
 * HTTPS as the browser sees it: the TLS-terminating proxy says so in X-Forwarded-Proto. A spoofed
 * "https" over plain HTTP only yields a Secure cookie the browser then refuses — no weaker setting.
 */
async function isHttps(): Promise<boolean> {
  const proto = (await headers()).get("x-forwarded-proto");
  return proto?.split(",")[0]?.trim() === "https";
}
