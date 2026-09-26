Not planner-validated
HypoPG cannot create hypothetical GIN or GiST indexes, so PgLens could not ask the planner whether this
index would be used or how much it would change the cost. There is no estimate for it; whether it helps
must be checked by building it on a copy.
Docs: https://hypopg.readthedocs.io/en/rel1_stable/
