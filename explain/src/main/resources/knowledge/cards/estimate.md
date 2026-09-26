Planner estimates are not runtimes
PgLens checks an index with HypoPG: it creates the index hypothetically (nothing is built) and asks the
planner to plan the query again. The planner's cost is a unitless estimate of work, not a time in
milliseconds. A lower cost means the planner expects less work; it does not measure or promise a faster
query.
Docs: https://www.postgresql.org/docs/16/using-explain.html
Docs: https://hypopg.readthedocs.io/en/rel1_stable/
