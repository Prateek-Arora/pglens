# Contributing to PgLens

Thanks for your interest! PgLens is early (Phase 0). This stub will grow as the
project does.

## Development environment

**Prerequisites:** Docker + the Compose plugin, and GNU Make. Start Docker
Desktop, then:

```bash
make up        # start the databases
make seed      # load demo data
make warmup    # accumulate pg_stat_statements
make test      # run the smoke test
make down      # stop (make clean also removes data volumes)
```

## Before you open a PR

- Run `make lint` (shell, Dockerfile, SQL) and `make test`. Both run in CI on
  every push and pull request.
- **Linters/formatters are the source of truth for style** — match what they
  enforce; we don't restate style rules in prose.
- Keep changes scoped to the current phase (see [`docs/project.md`](docs/project.md)).
  New behavior comes with tests.

## Principles we hold to

- **No fabricated evidence** — every number is real and labeled.
- **Safe by default** — the monitored database is read-only; never write to it.
- **Deterministic core, optional AI** — analysis must be correct with the LLM off.

By contributing you agree your contributions are licensed under the
[Apache-2.0 License](LICENSE).
