-- V5 (Phase 2 hardening, ADR-0035). Lease-reclaim + dead-letter for the validation work-queue.
--
-- Without this, a job stuck in LEASED (the agent crashed or a validation threw before reporting) stays
-- LEASED forever, and the V1 partial-unique validation_jobs_inflight_uniq (PENDING|LEASED) then blocks
-- re-enqueuing that candidate — so a query whose FIRST validation attempt failed would silently never
-- get its index recommendation. The singleton analysis pass now reclaims a LEASED job whose lease has
-- timed out back to PENDING (bumping attempts), and dead-letters it to FAILED past a max-attempts cap
-- so a genuinely-poison candidate can't reclaim-loop forever.
--
-- This is the fence-less half of ADR-0034's F4: safe here because PgLens is one (single-threaded) agent
-- per db, so there is no concurrent worker to fence. A fencing token for true concurrent validation
-- workers remains backlog B12 (a Phase-5 scale concern).

-- Per-job lease attempt counter. 0 until the first reclaim; the reclaim bumps it and dead-letters at the
-- max-attempts cap (pglens.analysis.max-validation-attempts).
ALTER TABLE validation_jobs ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;

-- Keeps the reclaim scan (LEASED jobs whose leased_at is older than the timeout) tight; partial on the
-- small set of currently-leased jobs, mirroring validation_jobs_pending_idx.
CREATE INDEX validation_jobs_leased_idx
    ON validation_jobs (leased_at)
    WHERE state = 'LEASED';
