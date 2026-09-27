# Contributing to PgLens

Thanks for your interest! PgLens is a release candidate (`v0.1.0-rc`); see
[`docs/project.md`](docs/project.md) for where it's heading and
[`docs/backlog.md`](docs/backlog.md) for work that's been thought through but deferred.

## Development environment

**Prerequisites:** Docker + the Compose plugin and GNU Make to run the stack; a JDK 17+ (the
Gradle wrapper provisions JDK 21) for the Java tests; Node 24 + pnpm for the dashboard.

```bash
make up          # build + start everything (databases, server, agent, dashboard)
make register    # register the demo database and give its agent a token
make seed        # load demo data
make warmup      # accumulate pg_stat_statements
./gradlew build  # Java: format check, unit + integration tests (needs Docker)
make e2e         # dashboard end-to-end (Playwright + axe)
make down        # stop (make clean also removes data volumes)
```

Test engine changes on more than the demo schema: `RealWorldSchemaIntegrationTest` covers
non-public schemas, quoted names, `varchar`, partitions and a least-privilege role — extend it
when you add a rule.

## Before you open a PR

- Run `make hooks` once after cloning. Every commit then scans its staged changes for secrets
  (gitleaks) and refuses local-only files. CI also scans the full history (`make secrets` runs the
  same scan locally). Never commit real credentials; `.env` is git-ignored, and only
  `deploy/compose/.env.example` with dev defaults is tracked.

- Run `make lint` (shell, Dockerfile, SQL) and `make test`. Both run in CI on
  every push and pull request.
- **Linters/formatters are the source of truth for style** — match what they
  enforce; we don't restate style rules in prose.
- Keep changes scoped (see [`docs/project.md`](docs/project.md)). New behavior comes with tests.
- Report security issues privately — see [`SECURITY.md`](SECURITY.md).

## Principles we hold to

- **No fabricated evidence** — every number is real and labeled.
- **Safe by default** — the monitored database is read-only; never write to it.
- **Deterministic core, optional AI** — analysis must be correct with the LLM off.

By contributing you agree your contributions are licensed under the
[Apache-2.0 License](LICENSE).
