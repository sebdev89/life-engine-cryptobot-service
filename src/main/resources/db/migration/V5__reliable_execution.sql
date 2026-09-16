-- V5 — KAN-403: reliable financial execution.
--
-- Endgame §31: Kubernetes keeps processes alive; the application keeps messages and state.
--   * operation_id  — idempotency key of an execution, unique across proposals, written in the
--                     same transaction that moves the row to EXECUTING (before anything is signed).
--   * version       — optimistic lock: every commit expects the version it read and bumps it.
--   * SUBMITTED     — new status between EXECUTING and EXECUTED|FAILED: sendTransaction returned.
--   * outbox_event  — business events written in the same transaction as the state; a worker
--                     publishes them with SELECT … FOR UPDATE SKIP LOCKED and exponential backoff.
--   * dead_letter   — what could not be resolved automatically (exhausted outbox retries,
--                     ambiguous trades). Never discarded; dlq_size counts the unresolved rows.

ALTER TABLE action_proposal ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE action_proposal ADD COLUMN operation_id UUID;

CREATE UNIQUE INDEX uq_action_proposal_operation
    ON action_proposal (operation_id)
    WHERE operation_id IS NOT NULL;

ALTER TABLE action_proposal DROP CONSTRAINT chk_action_proposal_status;
ALTER TABLE action_proposal ADD CONSTRAINT chk_action_proposal_status CHECK (status IN (
    'PROPOSED', 'SIMULATED', 'BLOCKED_BY_POLICY', 'AWAITING_APPROVAL', 'APPROVED',
    'REJECTED', 'EXECUTING', 'SUBMITTED', 'EXECUTED', 'FAILED', 'EXPIRED'));

DROP INDEX idx_action_proposal_owner_open;
CREATE INDEX idx_action_proposal_owner_open
    ON action_proposal (owner_user_id, created_at DESC)
    WHERE status IN ('AWAITING_APPROVAL', 'APPROVED', 'EXECUTING', 'SUBMITTED');

-- The reconciler's work list: in-flight rows, oldest update first.
CREATE INDEX idx_action_proposal_in_flight
    ON action_proposal (updated_at)
    WHERE status IN ('EXECUTING', 'SUBMITTED');

CREATE TABLE outbox_event (
    id               UUID         PRIMARY KEY,
    aggregate_type   VARCHAR(48)  NOT NULL,
    aggregate_id     UUID         NOT NULL,
    owner_user_id    UUID         NOT NULL,
    event_type       VARCHAR(48)  NOT NULL,
    payload          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts         INT          NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_error       TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at     TIMESTAMPTZ,

    CONSTRAINT chk_outbox_event_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- What the publisher scans: due PENDING rows, oldest first.
CREATE INDEX idx_outbox_event_due
    ON outbox_event (next_attempt_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_event_aggregate
    ON outbox_event (aggregate_id, created_at);

CREATE TABLE dead_letter (
    id             UUID         PRIMARY KEY,
    source         VARCHAR(24)  NOT NULL,
    ref_id         UUID         NOT NULL,
    proposal_id    UUID,
    owner_user_id  UUID,
    reason         TEXT         NOT NULL,
    payload        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at    TIMESTAMPTZ,

    CONSTRAINT chk_dead_letter_source CHECK (source IN ('OUTBOX', 'RECONCILIATION'))
);

CREATE INDEX idx_dead_letter_unresolved
    ON dead_letter (created_at)
    WHERE resolved_at IS NULL;

CREATE INDEX idx_dead_letter_proposal
    ON dead_letter (proposal_id, created_at);
