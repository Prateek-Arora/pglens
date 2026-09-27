import type { Route } from "next";

/**
 * The page to return to after login, from a `?next=` value. Only a local path is allowed — anything
 * else (`//evil.example`, `https://…`, `/\evil`) would turn the login page into an open redirect.
 */
export function safeNext(value: unknown, fallback: Route = "/"): Route {
  if (typeof value !== "string" || !value.startsWith("/")) return fallback;
  if (value.startsWith("//") || value.startsWith("/\\")) return fallback;
  if (/[\u0000-\u001f]/.test(value)) return fallback;
  return value as Route; // a local path; a page that doesn't exist renders the not-found page
}
