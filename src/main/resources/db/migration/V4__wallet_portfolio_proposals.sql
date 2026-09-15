-- V4 — Colosseum MVP: wallets, portfolio snapshots, advisor chat, action proposals, audit trail.
--
-- Design: each aggregate is stored as a JSONB `doc` (the full Java record, serialised by
-- Jackson) next to the handful of columns we filter/sort on. The domain records are the
-- schema; adding a field to a record is not a migration. Tenancy is `owner_user_id`
-- (the JWT `sub`), resolved server-side on every query.
--
-- Nothing here ever stores a private key, a seed, or a signed transaction.

CREATE TABLE wallet (
    id             UUID         PRIMARY KEY,
    owner_user_id  UUID         NOT NULL,
    address        VARCHAR(64)  NOT NULL,
    cluster        VARCHAR(16)  NOT NULL,
    label          VARCHAR(128),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_wallet_owner_address_cluster UNIQUE (owner_user_id, address, cluster),
    CONSTRAINT chk_wallet_cluster CHECK (cluster IN ('devnet', 'mainnet-beta'))
);

CREATE INDEX idx_wallet_owner ON wallet (owner_user_id, created_at DESC);

CREATE TRIGGER tr_wallet_updated_at
    BEFORE UPDATE ON wallet
    FOR EACH ROW
    EXECUTE PROCEDURE cryptobot_touch_updated_at();

CREATE TABLE portfolio_snapshot (
    id           UUID          PRIMARY KEY,
    wallet_id    UUID          NOT NULL REFERENCES wallet (id) ON DELETE CASCADE,
    captured_at  TIMESTAMPTZ   NOT NULL,
    total_usd    NUMERIC(24,8) NOT NULL DEFAULT 0,
    doc          JSONB         NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_portfolio_snapshot_wallet_time ON portfolio_snapshot (wallet_id, captured_at DESC);

CREATE TABLE advisor_message (
    id              UUID         PRIMARY KEY,
    wallet_id       UUID         NOT NULL REFERENCES wallet (id) ON DELETE CASCADE,
    owner_user_id   UUID         NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    runtime_run_id  UUID,
    doc             JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_advisor_message_role CHECK (role IN ('user', 'assistant'))
);

CREATE INDEX idx_advisor_message_wallet_time ON advisor_message (wallet_id, created_at DESC);

CREATE TABLE action_proposal (
    id              UUID         PRIMARY KEY,
    wallet_id       UUID         NOT NULL REFERENCES wallet (id) ON DELETE CASCADE,
    owner_user_id   UUID         NOT NULL,
    status          VARCHAR(24)  NOT NULL,
    kind            VARCHAR(32)  NOT NULL,
    runtime_run_id  UUID,
    doc             JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_action_proposal_status CHECK (status IN (
        'PROPOSED', 'SIMULATED', 'BLOCKED_BY_POLICY', 'AWAITING_APPROVAL', 'APPROVED',
        'REJECTED', 'EXECUTING', 'EXECUTED', 'FAILED', 'EXPIRED'))
);

CREATE INDEX idx_action_proposal_wallet_time ON action_proposal (wallet_id, created_at DESC);
CREATE INDEX idx_action_proposal_owner_open
    ON action_proposal (owner_user_id, created_at DESC)
    WHERE status IN ('AWAITING_APPROVAL', 'APPROVED', 'EXECUTING');

CREATE TRIGGER tr_action_proposal_updated_at
    BEFORE UPDATE ON action_proposal
    FOR EACH ROW
    EXECUTE PROCEDURE cryptobot_touch_updated_at();

-- Append-only: no updated_at, no trigger, no UPDATE path in code.
CREATE TABLE audit_event (
    id             UUID         PRIMARY KEY,
    owner_user_id  UUID         NOT NULL,
    wallet_id      UUID,
    proposal_id    UUID,
    event_type     VARCHAR(48)  NOT NULL,
    actor          VARCHAR(160) NOT NULL,
    payload        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_event_proposal ON audit_event (proposal_id, created_at);
CREATE INDEX idx_audit_event_wallet ON audit_event (wallet_id, created_at DESC);
