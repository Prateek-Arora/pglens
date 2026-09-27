---
description: Conventions for the Next.js dashboard (dashboard/)
paths:
  - "dashboard/**"
---
- **Read the bundled docs first.** Next 16 differs from older training data (`middleware` → `proxy`,
  async request APIs, no `next lint`). The docs for the installed version are in
  `dashboard/node_modules/next/dist/docs/`; check them before writing Next code.
- **Node 24:** run `source ~/.nvm/nvm.sh && nvm use 24.21.0` before any `pnpm` command (the host
  default is 22). Checks: `pnpm lint`, `pnpm typecheck` (runs `next typegen` first), `pnpm test`,
  `pnpm build`, `pnpm format:check`.
- **Only `src/lib/api/server.ts` calls the API** (ADR-0046). Pages use `read(...)` (401 → login,
  404 → not-found, else the error boundary); Server Actions use `attempt(...)` to show the API's
  message. Never call the API from a client component, never put the token anywhere but the httpOnly
  cookie, and never cache a response across requests or users.
- **`src/proxy.ts` is not auth.** It sets the CSP nonce and the `x-pglens-path` header, nothing else.
  The layout reads the user for display only; each page's own API calls are the access check.
- **Types come from the committed spec:** after a server response change, `make openapi` (repo root)
  then `pnpm api:types`, and commit both files. Never edit `src/lib/api/schema.d.ts` by hand. A
  field typed `T | null` means "no value" is real — render it as such, don't coerce it to 0.
- **Every number goes through a labeled component** (`<Measured>`, `<Estimate>`, `<Count>`), so the
  planner-estimate / measured distinction reaches tooltips and charts (charter principle 1).
- **shadcn components are copied in** (`src/components/ui/`). `shadcn add` writes
  `import { cn } from "cn"` — change it to `@/lib/utils` (we don't depend on `cn`). Its CLI needs
  `pnpm dlx shadcn@<pinned version>`.
- **Supply chain:** exact versions only; pnpm's age gate and trust policy are strict
  (`pnpm-workspace.yaml`). A trust-policy hit gets checked by hand before an exact-version exclude —
  never a blanket one.
- **Tests:** unit tests beside the code (`*.test.ts[x]`, Vitest + Testing Library); e2e in `e2e/`
  (Playwright + axe) against a running stack: `PGLENS_DASHBOARD_URL`, `PGLENS_ADMIN_PASSWORD`
  (from `deploy/compose/.env` — never print it); locally `PLAYWRIGHT_CHANNEL=chrome` if the bundled
  Chromium isn't installed.
- **Visual language (ADR-0048, "Drafting Sheet"; see `DESIGN.md`):** colors only as tokens in
  `globals.css` (light film + dark blueprint, OS-driven). `primary` = actionable, `success` =
  validated/healthy, `finding` (redline) = plan problem, `destructive` = danger; each metric has one
  ink (`METRIC_INK`). Measured = solid, estimate = dashed/hatched (`<Estimate>`, `ScaleBar estimate`).
  Every query renders through `SqlInline`/`SqlBlock` (never raw mono text). Content sits on `.sheet`s.
  Inputs, `NativeSelect` and buttons share one 36 px height; help text goes under a form row. Before
  calling UI work done, screenshot **every** page at 1440 and 390 px in **both themes** and look at
  them, plus axe.
