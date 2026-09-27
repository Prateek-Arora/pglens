import { NextResponse, type NextRequest } from "next/server";

/**
 * Sets a per-request Content-Security-Policy with a script nonce (Next reads it from the request
 * header and stamps its own scripts), and passes the requested path on so a lapsed session can
 * return to it after login. That is all: the proxy is never an auth check — the API decides who
 * may see what, and the data-access layer asks it on every request.
 *
 * Styles allow 'unsafe-inline': Shiki's highlighted SQL and Recharts render inline style attributes,
 * which a nonce cannot cover. Scripts, the part that matters, stay nonce-only.
 */
export function proxy(request: NextRequest) {
  const nonce = Buffer.from(crypto.randomUUID()).toString("base64");
  const dev = process.env.NODE_ENV === "development";
  const csp = [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${dev ? " 'unsafe-eval'" : ""}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join("; ");

  const headers = new Headers(request.headers);
  headers.set("x-nonce", nonce);
  headers.set("x-pglens-path", request.nextUrl.pathname + request.nextUrl.search);
  headers.set("Content-Security-Policy", csp);
  const response = NextResponse.next({ request: { headers } });
  response.headers.set("Content-Security-Policy", csp);
  return response;
}

export const config = {
  matcher: [
    {
      // Pages only: not static assets, and not link prefetches (they get the header on navigation).
      source: "/((?!_next/static|_next/image|favicon.ico).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
