# PgLens dashboard

The web UI (Next.js App Router, React, TypeScript). It is a backend-for-frontend: the browser talks
only to this app, and its server code calls the PgLens API. Design and the _why_: ADR-0046 in
[`docs/decisions.md`](../docs/decisions.md), and the Dashboard section of
[`docs/architecture.md`](../docs/architecture.md).

## Develop

Node **24.21.0** and pnpm (via corepack). With the stack running (`make up` at the repo root):

```bash
corepack enable
pnpm install
PGLENS_API_URL=http://localhost:8080 pnpm dev   # http://localhost:3000
```

| Command                                              | What it does                                                         |
| ---------------------------------------------------- | -------------------------------------------------------------------- |
| `pnpm lint` · `pnpm typecheck` · `pnpm format:check` | ESLint, TypeScript (with Next's route types), Prettier               |
| `pnpm test`                                          | unit tests (Vitest + Testing Library)                                |
| `pnpm build`                                         | production build (`output: "standalone"`, used by the Docker image)  |
| `pnpm api:types`                                     | regenerate `src/lib/api/schema.d.ts` from `../docs/api/openapi.json` |
| `pnpm exec playwright test`                          | end-to-end + axe against a running stack (`make e2e` does it all)    |

## Layout

- `src/lib/api/server.ts` — the only code that calls the API (server-only).
- `src/lib/session.ts` — the httpOnly session cookie. `src/proxy.ts` — CSP nonce only, never auth.
- `src/components/numbers.tsx` — `<Measured>`, `<Estimate>`, `<Count>`: every number goes through one.
- `src/app/(app)/…` — the signed-in pages; `src/app/login` — sign-in.
- `e2e/` — Playwright specs.

After a server change to a response shape: `make openapi` at the repo root, then `pnpm api:types`,
and commit both files — CI fails if they drift.
