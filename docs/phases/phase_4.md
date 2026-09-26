# Phase 4 — Web API, Security, Dashboard (ship checkpoint)

> **Status: APPROVED 2026-09-26 — 4A in progress (Steps 0–5 built; DoD docs remain).** Verified per `../workflows/plan-verification.md`
> from the user's draft (`phase_4_dashboard_nextjs.md`, Desktop bundle).

## 1. Context & prerequisites

PgLens today: `pglens scan / explain / confirm` (CLI, no LLM needed) and a **headless** server:
agent → gRPC → server → metadata Postgres (history, validated recommendations, index hygiene,
trends, cached explanations). The server has **no HTTP listener** (`spring-boot-starter` only; its
compose healthcheck is a TCP connect to gRPC). Everything the dashboard needs is already persisted
or computable from persisted state:

| Needed on screen | Where it lives today |
|---|---|
| Leaderboard (windowed) | `query_stats` deltas (BRIN `captured_at`, V4) + `query_texts` |
| Query detail: SQL + plan | `query_texts.normalized_text`, `plan_json` (**GENERIC_PLAN** — estimates only, no actual rows) |
| Anti-patterns | *not persisted* — recomputed from `plan_json` with the pure detector (as `ExplanationInputs` does) |
| Recommendations (per index) | `AdviceService` read model (built for Phase 4, ADR-0038) |
| Index hygiene (unused/duplicate) | `index_hygiene` (B5, ADR-0029) — **missing from the draft** |
| Trends / top movers / new-slow | `TrendService` (ADR-0031) |
| Explanation | `TemplateExplainer` (pure) + `explanations` cache (LLM rows, only if the job is on) |

Prerequisites met: Phases 0–3 shipped (`v0.0.6`, merge `fcdb6af`). ADR-0037 moved **gRPC TLS, a
dashboard login and the registration API (B10)** into this phase — it is the `v0.1.0-rc` ship bar.

## 2. Verification of the draft — what was checked, corrected, added

**Confirmed:** the four screens, REST-first, read-only, a "legible-enough" plan tree, honest labels
through to the pixel, empty/error states, CI + smoke, the ADR-0037 security bar.

**Corrected / added (ranked by impact):**

1. **Spring Boot 3.5 is past OSS end-of-life (30 Jun 2026; 3.5.16 was the last free release).** We
   pinned it on 2026-08-25 when it was current. Phase 4 adds the server's first *internet-facing*
   surface (HTTP + a login) — shipping that on a framework with no more free security patches
   contradicts the "safe for a team to run" bar. → **Step 0: upgrade to Spring Boot 4.1**
   (OSS support to 31 Jul 2027). Needs Gradle ≥ 8.14 (we have 8.14.5 ✔) and Java 17+ (21 ✔).
   Boot 4 prefers **Jackson 3** (`tools.jackson`); Jackson 2 stays dependency-managed, so the pure
   modules (`engine`/`explain`/`cli`, 25 files) keep Jackson 2 for now and only the server's web
   layer uses Jackson 3. Full Jackson 3 migration → backlog. Plain grpc-java stays (ADR-0025 still
   holds; switching to Spring gRPC now would be churn with no user benefit).
2. **Next.js has had a heavy security year** — the RSC RCE (CVSS 10, Dec 2025), a middleware auth
   bypass (CVE-2025-29927), 13 advisories in May 2026, and a release with **one critical** advisory
   scheduled for **30 Sep 2026** (16.3.7). → pin the exact patched version (≥ 16.3.7), make every
   data route dynamic (no shared cache of per-user data), and **never rely on `proxy.ts` (ex-
   middleware) for auth** — the Spring API checks every request (defence in depth).
3. **`queryid` is a signed 64-bit integer; JavaScript numbers are exact only to 2⁵³.** A JSON
   number would silently corrupt ids in the browser (a wrong query detail page, a wrong cache key).
   → `queryid` is a **string** in every API response and URL.
4. **The plans are GENERIC_PLAN estimates.** The draft's "per-node cost/rows" must be labeled
   *estimated* rows/cost — no actuals exist server-side. And "highlight the offending node" needs a
   link the engine doesn't have: `Finding` has no node reference. → additive engine change: each
   finding records its plan node (pre-order index); `--json` 1.3 → **1.4** (additive).
5. **The "what should I do Monday" screen would rank planner estimates only**, while ADR-0041/0042
   measured that some planner-validated indexes make queries *slower* (JOB 18 %, TPC-H 4/14).
   → every recommendation card carries "planner-validated ≠ safe" and a **pre-filled
   `pglens confirm` command**. Importing confirm results into the server is deferred (**B24**).
6. **Index hygiene is missing from the draft** (unused / duplicate / redundant indexes — the
   *drop* side of the advice, already persisted). → a panel on the Recommendations screen.
7. **Works with no LLM** (user requirement, 2026-09-26, recorded in `project.md`): explanations
   are the template computed on read; a cached LLM row is used only when its facts hash still
   matches, and is labeled with its source + model.
8. **CORS is unnecessary.** The browser only talks to Next.js; Next.js server code calls the API on
   the private network (backend-for-frontend). The draft's "CORS config" step is dropped — one less
   thing to get wrong.
9. **Login over plain HTTP leaks the password.** gRPC gets TLS, so the dashboard must too: the
   cookie is `Secure` + `__Host-` prefixed when served over HTTPS, and the docs ship a
   reverse-proxy recipe (Caddy, automatic HTTPS). Local `http://localhost` is allowed.
10. **Flexibility (user requirement):** the REST API is a first-class product surface, not just the
    dashboard's backend — scripts/CI can use it with a named **API token**; the dashboard is an
    optional compose service; the CLI stays fully standalone.
11. **Scope:** this is the biggest phase so far. → two PRs / two tags so each half ships:
    **4A `v0.0.7`** (headless: Boot 4, TLS, auth, registration, REST API) and **4B `v0.1.0-rc`**
    (dashboard). 4A alone already replaces `make register` and makes the server scriptable.

## 3. Plan

### 4A — Secure, scriptable server (`v0.0.7`)

**Step 0 — Spring Boot 3.5.16 → 4.1.x.** Bump the catalog; fix compile/test breakages; keep
Jackson 2 in pure modules; full build + PG17/18 compat green before anything else. ADR records the
EOL finding.

**Step 1 — gRPC TLS (plaintext refused by default).**
- Server: `TlsServerCredentials` from PEM files (`PGLENS_GRPC_TLS_CERT` / `PGLENS_GRPC_TLS_KEY`); no
  cert configured → **fail fast** with the fix in the message, unless `PGLENS_GRPC_PLAINTEXT=true`
  (logged as a warning).
- Agent: TLS by default, trusting a CA file (`PGLENS_SERVER_CA_CERT`) or the JVM trust store, with an
  optional name override (`PGLENS_SERVER_AUTHORITY`); plaintext only with an explicit
  `PGLENS_SERVER_PLAINTEXT=true` (warning at every start).
- Compose: a one-shot `certs` service (pinned image with openssl) creates a dev CA + a server cert
  (SAN `server`, `localhost`) into a named volume; server + agent mount it read-only. Keys never
  touch the repo (git-ignored; gitleaks already scans for private keys).
- Tests: grpc-testing's bundled test certs; IT proves TLS works and plaintext is refused.

**Step 2 — HTTP listener + auth (one choke point, like ADR-0027).**
- `spring-boot-starter-web` + **Spring Security**, stateless: a single bearer-token filter resolves
  `Authorization: Bearer …` to a user or API token. Tokens are opaque random 32-byte values;
  only SHA-256 hashes are stored (same rule as agent tokens).
- Tables (Flyway **V9**): `users` (username, password hash — Spring's delegating encoder, bcrypt;
  role `ADMIN` | `VIEWER`), `sessions` (hash, user, expires; 8 h sliding, 7 d max), `api_tokens`
  (hash, name, user, last used; read-only).
- First boot with no users: admin password from `PGLENS_ADMIN_PASSWORD`, else a random one printed
  **once** to the log. Login throttling: in-memory failure counter per username (single
  server instance — stated assumption; *built per username, not + IP — see ADR-0044 Steps 1–3*).
- Endpoints: `POST /api/v1/auth/login`, `POST /api/v1/auth/logout`, `GET /api/v1/me`,
  admin user management (create/delete/reset), API-token create/list/revoke.
- **Local-only escape hatch** (flexibility): `pglens.auth.mode=none` for a single user on
  localhost; the API marks every response and the dashboard shows a permanent banner. Default is
  login required.

**Step 3 — Registration API (B10), replaces `make register`.**
`POST /api/v1/databases` {name} → returns the agent token **once**; rotate-token; delete (asks
for the name to confirm — it deletes that DB's history from the *metadata* store, never touches
the monitored DB). `make register` becomes a thin wrapper around the API.
*✅ Built 2026-09-26 (Steps 0–3; results in ADR-0044).*

**Step 4 — Read API (`/api/v1`, JSON, RFC 9457 problem details, OpenAPI via springdoc 3.x).**
- `GET /databases` — with freshness: last sample time, agent status (stale after 3 intervals).
- `GET /databases/{db}/queries?window=24h|7d|30d&sort=total|mean|calls&limit≤200&offset`
  — windowed leaderboard from `query_stats`, "has recommendation" flag.
- `GET /databases/{db}/queries/{queryid}` — normalized SQL, plan tree (node ids, estimated
  rows/cost), findings with node refs, this query's recommendations, window stats.
- `GET …/queries/{queryid}/trend?from&to` — series; `null` means stay `null` (a gap, not 0).
- `GET …/queries/{queryid}/explanation` — template on read, cached LLM row if the facts hash
  matches (labeled source/model/prompt version).
- `GET /databases/{db}/recommendations` (AdviceService), `…/hygiene`, `…/top-movers`,
  `…/new-slow`; `GET /recommendations` across DBs.
- Every numeric field's name says what it is (`plannerCostBefore`, `measuredMeanMs`,
  `estimatedMsSaved`); estimates travel with their label.
- Engine: `Finding` gains the node reference (Section 2 #4); `--json` 1.4.
*✅ Built 2026-09-26: endpoints, choices and live findings in ADR-0044 (Step 4 results). The
leaderboard carries the best verdict (`recommendation`), not a boolean; explanations are per
recommended index; trends take `resolution=raw|hour|auto`.*

**Step 5 — Dogfood the API's own speed.** Seed a large synthetic history (≈30 days × 5-min
samples × 500 queries ≈ 4.3 M `query_stats` rows) into a throwaway metadata DB, `EXPLAIN` each
read query, set a p95 budget (≤ 300 ms leaderboard at 30 d), fix with a migration if missed,
record in `docs/benchmarks.md`. A slow performance tool is the embarrassing failure mode.
*✅ Done 2026-09-26: budget missed (30-day leaderboard 741 ms p95, top movers 968 ms) → hourly
rollup, migration V10 (ADR-0045) → 44 ms / 45 ms. `make bench-api`; `docs/benchmarks.md`.*

**4A Definition of Done:** Boot 4.1 build + compat green · gRPC TLS on by default, plaintext
refused (IT) · login, sessions, API tokens, roles (IT incl. 401/403 paths) · registration through
the API, `make up` onboarding uses it · read API documented (OpenAPI) and tested against real
Testcontainers data · API p95 measured and recorded · README "use the API from a script" section ·
ADRs · teach-back check.

**4A teach-back** (answer without notes; the answers are in ADR-0044/0045 and `docs/architecture.md`).
*Deferred by the user (2026-09-26) to the end of Phase 4: one start-to-end pass with 4B's questions,
so `v0.0.7` ships without it.*
1. Why upgrade Spring Boot *before* writing any web code, and what broke silently in the upgrade?
2. How does the dev TLS setup make sure nothing can sign a second certificate, and why does the
   agent's container see only `ca.pem`?
3. Why opaque random tokens stored as SHA-256 hashes rather than JWTs — what does revoking one cost
   in each design?
4. Why do session timestamps come from the server's `Clock` instead of the database's `now()`?
5. Why is the login throttle keyed per username and not per IP, and what can an attacker still do?
6. What does the backend-for-frontend design remove (two things), and why must the API still check
   every request?
7. Why is `queryid` a string in the API?
8. How does the dashboard find the plan node a finding is about, and what has to stay in step for
   that to keep working?
9. When does the explanation endpoint show an LLM answer, and why never an older one?
10. Right after setup the leaderboard was empty while recommendations existed — why?
11. Why did the 30-day leaderboard take 741 ms, why a trigger-maintained hourly rollup rather than a
    materialized view or a write in application code, and what does it cost?

### 4B — Dashboard (`v0.1.0-rc`)

**Stack (pinned exact):** Next.js **16.3.x ≥ 16.3.7** (App Router), React 19, TypeScript strict,
Node **24 LTS**, **pnpm** (frozen lockfile; install scripts off by default), Tailwind CSS +
**shadcn/ui** (components copied into the repo — no UI-library lock-in), **Recharts 3** (client
components only), **Shiki** (SQL highlighting on the server — no client JS), `sql-formatter`
(PostgreSQL dialect), types generated from the OpenAPI spec (`openapi-typescript` +
`openapi-fetch`, drift fails CI). Tests: **Vitest + Testing Library**, **Playwright** e2e with
**axe** accessibility checks. Lint: ESLint 9 flat config + Prettier (`next lint` is gone in 16).
Image: `output: "standalone"`, non-root.

**Step 6 — Scaffold + auth plumbing.** `dashboard/` app; a server-only data-access module is the
*only* place that calls the API (reads the `httpOnly` session cookie, sends the bearer, 401 →
`/login`); login/logout as Server Actions; every data route dynamic; basic security headers.

**Step 7 — Screens.**
1. **Databases / onboarding** — empty state = "Add a database": registers it, shows the token
   once plus a copy-paste agent snippet. Freshness badge per DB; stale-agent banner.
2. **Leaderboard** — window + sort in the URL (shareable, server-rendered), recommendation badge.
   Empty state (found live, 2026-09-26): a query's first sighting only anchors its counters
   (ADR-0024), so right after setup the window can be empty while recommendations exist — say
   "activity counts from the agent's first sample; run your workload or wait one interval" and
   link the recommendations.
3. **Query detail** — formatted SQL, plan tree (estimated rows/cost, offending node highlighted,
   finding evidence beside it), recommendation with copy-DDL, planner-estimate label,
   value range, build caution, write load, "planner-validated ≠ safe" + pre-filled
   `pglens confirm` command, explanation (template or labeled LLM).
4. **Trends** — per-query mean/total over time (honest axes, gaps for nulls), top movers, new-slow.
5. **Recommendations** — ranked per index across DBs with evidence + DDL; **hygiene panel**.
6. **Settings (admin)** — users, API tokens, databases (rotate/delete).

**Step 8 — Honesty components.** Every number renders through `<Estimate>` / `<Measured>` /
`<Count>` components that carry the label into tooltips and charts; unit tests assert labels.

**Step 9 — Compose, CI, docs.** `dashboard` service in compose (port 3000; API port bound to
`127.0.0.1` only); CI `dashboard` job (install, lint, typecheck, unit, build, OpenAPI drift);
smoke runs Playwright: login → add DB → data appears → detail → copy DDL; README quickstart
rewritten and timed from a cold clone; reverse-proxy (Caddy) recipe for HTTPS.

**Step 10 (optional, cut first) — GraphQL read layer.** Spring for GraphQL over the same service
methods, read-only, one schema. Only claimed as a skill if completed (ADR-0037).

**4B Definition of Done:** `make up` (or `docker compose up`) starts the dashboard; from a cold
clone a new user logs in, registers a DB, starts an agent and sees real data in < 10 min (timed,
recorded) · all five screens show real data with correct labels · works with the LLM off (tested)
· empty/loading/error states · Playwright + axe pass in CI · security: login required by default,
tokens hashed, plaintext gRPC refused, no per-user data cached across users · a stranger can
answer "what should this team fix first, and why?" from the UI in under a minute · teach-back
check · tag `v0.1.0-rc`.

## 4. Deliberately skipped (YAGNI) → backlog where useful

- **B24 — import `pglens confirm` results** into the server so measured verdicts sit beside
  estimates (the natural next step after this phase).
- **B25 — full Jackson 3 migration** of the pure modules (Jackson 2 is still managed by Boot 4).
- OIDC/SSO (Phase 6), fine-grained RBAC beyond admin/viewer, multi-tenant orgs, mTLS agent
  identity (TLS + token is the bar), certificate hot-reload, live updates (WebSocket/SSE — a
  refresh is enough at 5-min sampling), dark mode, i18n, any write to the monitored DB (never).

## 5. Best fast resource per new skill

- **Next.js App Router:** the official *Learn* course (nextjs.org/learn) — skip the Pages Router and
  Vercel-deploy chapters; read *Authentication → Data Access Layer* in the docs.
- **Spring Security (stateless API):** the reference's *Servlet Applications → Architecture* page
  — skip OAuth2/SAML chapters.
- **gRPC TLS:** grpc-java `SECURITY.md` — skip ALPN/OpenSSL-provider details (the shaded Netty
  handles it).
- **Spring Boot 4 upgrade:** the official *Spring Boot 4.0 Migration Guide* wiki — skip modules we
  don't use.
- **GraphQL (if done):** the Spring for GraphQL reference *Boot Starter* page.

## 6. Risks

| Risk | Mitigation |
|---|---|
| Boot 4 upgrade breaks something subtle | Step 0 alone, full build + compat before new code |
| Next.js advisories keep coming | exact pin, API is the auth authority, CI dependency audit |
| Scope | two PRs/tags; GraphQL cut first |
| Plan-tree rabbit hole | tree + one highlighted node + evidence; no pgMustard clone |
| A number loses its label in a chart | honesty components + tests (Step 8) |

## 7. Sources (checked 2026-09-26)

- Spring Boot support dates: <https://endoflife.date/spring-boot>, <https://spring.io/support-policy/>
- Boot 4 migration + Jackson 3: <https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide>
- Next.js security releases: <https://nextjs.org/blog/upcoming-nextjs-security-release-september-22-2026>,
  <https://vercel.com/changelog/next-js-may-2026-security-release>,
  <https://react.dev/blog/2025/12/03/critical-security-vulnerability-in-react-server-components>
- Next.js 16 upgrade notes (`next lint` removed, `middleware` → `proxy`): <https://nextjs.org/docs/app/guides/upgrading/version-16>
- springdoc 3.x for Boot 4: <https://springdoc.org/>
- grpc-java TLS: <https://grpc.github.io/grpc-java/javadoc/io/grpc/TlsServerCredentials.html>
