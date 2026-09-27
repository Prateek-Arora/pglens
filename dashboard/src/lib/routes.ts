import type { Route } from "next";

/**
 * Every dynamic URL in the app, built in one place: segments are encoded here, and the `as Route`
 * cast Next's typed routes need for non-literal strings lives here instead of in every page.
 */
const seg = encodeURIComponent;

function withQuery(path: string, query?: Record<string, string | undefined>): Route {
  const q = new URLSearchParams(
    Object.entries(query ?? {}).filter((e): e is [string, string] => e[1] !== undefined),
  ).toString();
  return (q ? `${path}?${q}` : path) as Route;
}

export const routes = {
  database: (db: string, query?: Record<string, string | undefined>) =>
    withQuery(`/db/${seg(db)}`, query),
  trends: (db: string, query?: Record<string, string | undefined>) =>
    withQuery(`/db/${seg(db)}/trends`, query),
  recommendations: (db: string) => `/db/${seg(db)}/recommendations` as Route,
  query: (db: string, queryid: string, query?: Record<string, string | undefined>) =>
    withQuery(`/db/${seg(db)}/queries/${seg(queryid)}`, query),
};
