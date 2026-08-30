---
description: Conventions for the collector agent (:agent)
paths:
  - "agent/**"
---
- **Reuses the `:engine` DB I/O half only** — `StatsReader`, `PlanCapturer`, `CatalogReader`,
  `DataSources`. Never the pure analysis half (`parse`/`detect`/`candidate`/`rank`): that runs on the
  **server** (ADR-0023). If the agent needs a new monitored-DB read, add it to the engine `db` half
  (the single owner of monitored-DB SQL), not inline in the agent.
- **The agent is stateless.** It forwards **raw cumulative** counters + the global
  `pg_stat_statements_info.stats_reset`; the **server** computes per-interval deltas (ADR-0024).
  Never compute a delta, or a per-interval mean, on the agent — a sampled lifetime mean on the wire
  would be a fabricated interval number (charter #1). The lifetime `mean_exec_time` is intentionally
  not mapped onto `QueryStatSample`.
- **Read-only is enforced at TWO independent layers** (defense in depth). (1) The agent **logs in as
  the least-privilege `pglens_ro` role** (ADR-0030 — provisioned by the monitored image's
  `20_pglens_ro.sql`; `NOSUPERUSER`, `SELECT`-only + `pg_read_all_stats`, **no write grant**), so the
  DB itself rejects a write with "permission denied" regardless of session state. (2) The sampler
  calls `DataSources.applySessionGuards` at the start of each cycle (idempotent `SET … READ ONLY` +
  statement/lock timeouts) so the guard survives a reconnect. Never weaken either: don't grant the
  role more than it needs, never rely on a framework `readOnly` flag, never open a writable
  connection to the monitored DB.
- **One monitored-DB connection** (`SingleConnectionDataSource` via `DataSources.forScan`). HypoPG
  edge validation (Step 5) needs a session-local connection for its hypothetical indexes; the sampler
  shares that one connection. On a monitored-DB error, `resetConnection()` and retry next interval.
- **Register each query's text + plan once**, keyed by `queryid`, and only mark it registered **after
  a successful send** — a failed send leaves it unmarked so it is re-sent next interval (the server
  upserts texts idempotently). A failed send loses no data window: server deltas are anchored to the
  last *persisted* snapshot, not to any single send.
- **gRPC = plain grpc-java client** (ADR-0025). The bearer token goes in the `x-pglens-token`
  metadata header — it must match the server's `AuthInterceptor.TOKEN_HEADER`. Calls are short-lived
  client-streaming per interval (no long-idle stream for an LB to kill; ADR-0023). Plaintext for now;
  mTLS is Phase 6.
- **Failure is per-cycle and non-fatal** — catch, log, and let the next `@Scheduled` tick retry.
  Never let a sample cycle throw past the scheduler.
- **Keep this module consumable as a library.** The bootJar is named `app.jar` (for the Docker
  COPY), but **don't disable the plain `jar`** — the full-loop IT consumes `:agent` as a test
  dependency, and a disabled `jar` empties the runtime variant so `project(":agent")` resolves
  nothing (ADR-0032).
- **Tests:** pure mappers/hash are unit-tested with no container (`./gradlew :agent:test`). The
  end-to-end agent→server→DB flow lives in **`:server`** (`AgentToServerFlowIntegrationTest`,
  `@Tag("it")`, test-dep on `:agent`) — it boots the server + two containers and drives the *real*
  agent components (`SampleCollector`/`ValidationRunner`) directly; put cross-module flow assertions
  there, not here. Assert on wire contents / persisted rows, never wall-clock. Style is
  google-java-format via Spotless (`spotlessApply`); don't hand-format.
