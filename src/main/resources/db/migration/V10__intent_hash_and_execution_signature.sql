-- V10 — KAN-500 (CB-03/09): the two identities of an execution become columns.
--
--   * intent_hash          — H_I = sha256:<64 hex> of the canonical intent (KAN-435), as the caller
--                            presented it in Idempotency-Key. Until now it was only folded into
--                            operation_id (first 128 bits) and lost; now it is persisted in the same
--                            commit that moves the row to EXECUTING, next to the operation it derives.
--                            NULL when the caller used a plain UUID key (no intent hash was presented).
--   * execution_signature  — the on-chain transaction signature (base58 of the first signature = the
--                            transaction id), written at SIGNED — before broadcast — and kept through
--                            SUBMITTED/EXECUTED/FAILED. A retry (KAN-571) overwrites it with the new
--                            signature; the superseded one stays in the document (previousSignature).
--
-- Backfill is NULL on purpose: existing rows keep the JSONB document as their authority (it already
-- carries execution.signature; no row ever carried an intent hash). No UPDATE runs here, so the
-- updated_at trigger does not touch in-flight rows the reconciler is watching.

ALTER TABLE action_proposal ADD COLUMN intent_hash VARCHAR(71);
ALTER TABLE action_proposal ADD COLUMN execution_signature VARCHAR(96);

ALTER TABLE action_proposal ADD CONSTRAINT chk_action_proposal_intent_hash
    CHECK (intent_hash IS NULL OR intent_hash ~ '^sha256:[0-9a-f]{64}$');

-- Look-ups by what the chain knows (reconciliation, explorer links, audits by signature).
CREATE INDEX idx_action_proposal_execution_signature
    ON action_proposal (execution_signature)
    WHERE execution_signature IS NOT NULL;

-- The same intent may be executed at most once: the operation_id derived from it is already unique
-- (uq_action_proposal_operation); this index makes the hash itself searchable.
CREATE INDEX idx_action_proposal_intent_hash
    ON action_proposal (intent_hash)
    WHERE intent_hash IS NOT NULL;
