# Contributing to PgLens

Thanks for looking. PgLens is maintained by one person in their spare time, so small, focused pull
requests get reviewed fastest. Issues labelled `good first issue` are self-contained places to
start, and [docs/backlog.md](docs/backlog.md) lists work that has been thought through but not built
yet, with the reasoning.

## Set up

You need Docker with Compose and GNU Make to run the stack, a JDK 17 or newer for the Java tests
(the Gradle wrapper downloads JDK 21), and Node 24 with pnpm for the dashboard.

```bash
make up          # build and start everything, demo included (make up-no-demo: PgLens alone)
make register    # register the demo database and give its agent a token
make seed        # load the demo data
make warmup      # run the demo workload
./gradlew build  # Java: format check, unit and integration tests (needs Docker)
make e2e         # dashboard end-to-end tests (Playwright and axe)
make down        # stop (make clean also deletes the data volumes)
```

If you change the analysis engine, test it on more than the demo schema.
`RealWorldSchemaIntegrationTest` covers non-public schemas, quoted names, `varchar`, partitions and a
least-privilege role. Extend it when you add a rule.

## Before you open a pull request

- Run `make hooks` once after cloning. Every commit then scans its staged changes for secrets with
  gitleaks. CI scans the whole history too, and `make secrets` runs the same scan locally. `.env` is
  git-ignored; only `deploy/compose/.env.example` is tracked.
- Run `make lint` and `make test`. CI runs both, plus everything above, on every pull request.
- Style is whatever the formatters and linters enforce, so there are no style rules to read.
- New behaviour comes with tests.
- Report security problems privately, as described in [SECURITY.md](SECURITY.md).

## What PgLens holds to

- Every number is real and says what it is: measured, or a planner estimate.
- The monitored database is read-only. PgLens never writes to it.
- The analysis must be correct with the LLM turned off.

Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md). By contributing, you agree
that your contributions are licensed under [Apache-2.0](LICENSE).
