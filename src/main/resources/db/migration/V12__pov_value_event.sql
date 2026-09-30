-- V12 — KAN-818: Proof of Value V1 (ValueEvent core).
--
-- A ValueEvent is an ACCEPTED contribution (the five stages MERGED, BUILT, DEPLOYED, RUNNING and
-- ACCEPTED all true). It is one more Decision Receipt: an `intelligence_receipt` of kind VALUE_EVENT
-- whose output hash is the SHA-256 of the event's canonical JSON (RFC 8785). That receipt goes into
-- the existing Merkle batch of the anchoring sweep and its root into the `ir/1` memo on Solana
-- devnet — the signer and the memo format do not change.
--
-- Tenancy: the same key the receipts use (`tenant_id` = the JWT owner, resolved server-side; V6).
-- Nothing here is a secret: ids, labels, hashes and timestamps.

ALTER TABLE intelligence_receipt DROP CONSTRAINT chk_intelligence_receipt_kind;
ALTER TABLE intelligence_receipt ADD CONSTRAINT chk_intelligence_receipt_kind CHECK (kind IN (
    'WALLET_SNAPSHOT', 'HUMAN_IDEA', 'MARKET_ANALYSIS', 'RISK_DECISION', 'STRATEGY',
    'SIMULATION', 'EXECUTION', 'PROJECT_ANALYSIS', 'VALUE_EVENT'));

-- Who contributes: a human or an agent. An agent answers to an owner (and optionally an operator).
CREATE TABLE pov_identity (
    tenant_id     VARCHAR(64)  NOT NULL,
    id            VARCHAR(64)  NOT NULL,
    kind          VARCHAR(8)   NOT NULL,
    display_name  VARCHAR(120) NOT NULL,
    wallet        VARCHAR(64),
    owner_id      VARCHAR(64),
    operator_id   VARCHAR(64),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    PRIMARY KEY (tenant_id, id),
    CONSTRAINT chk_pov_identity_kind CHECK (kind IN ('HUMAN', 'AGENT')),
    CONSTRAINT fk_pov_identity_owner FOREIGN KEY (tenant_id, owner_id) REFERENCES pov_identity (tenant_id, id),
    CONSTRAINT fk_pov_identity_operator FOREIGN KEY (tenant_id, operator_id) REFERENCES pov_identity (tenant_id, id)
);

-- The accepted outcome. `receipt_hash` is the verifiable id (it is what enters the Merkle tree);
-- `value_event_hash` = SHA-256(canonical) = the receipt's output hash; `canonical` is the exact JSON
-- that was hashed, so anyone can recompute it.
CREATE TABLE pov_value_event (
    id                   UUID         PRIMARY KEY,
    tenant_id            VARCHAR(64)  NOT NULL,
    owner_id             UUID         NOT NULL,
    receipt_hash         VARCHAR(71)  NOT NULL UNIQUE REFERENCES intelligence_receipt (receipt_hash),
    value_event_hash     VARCHAR(71)  NOT NULL,
    project_id           VARCHAR(64)  NOT NULL,
    task_id              VARCHAR(64)  NOT NULL,
    title                VARCHAR(200) NOT NULL,
    artifact_hash        VARCHAR(71)  NOT NULL,
    acceptance_hash      VARCHAR(71)  NOT NULL,
    accepted_at          TIMESTAMPTZ  NOT NULL,
    distribution_policy  VARCHAR(64)  NOT NULL,
    total_units          INTEGER      NOT NULL,
    canonical            TEXT         NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_pov_value_event_hash UNIQUE (tenant_id, value_event_hash),
    CONSTRAINT chk_pov_value_event_hashes CHECK (value_event_hash ~ '^sha256:[0-9a-f]{64}$'
        AND artifact_hash ~ '^sha256:[0-9a-f]{64}$' AND acceptance_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_pov_value_event_units CHECK (total_units > 0)
);

CREATE INDEX idx_pov_value_event_tenant ON pov_value_event (tenant_id, created_at DESC);
CREATE INDEX idx_pov_value_event_task   ON pov_value_event (tenant_id, task_id);

-- Who did what in one event, and the units the distribution policy gave them. `position` keeps the
-- request order: the equal split gives the remainder to position 0, deterministically.
CREATE TABLE pov_contribution (
    id              UUID         PRIMARY KEY,
    value_event_id  UUID         NOT NULL REFERENCES pov_value_event (id),
    tenant_id       VARCHAR(64)  NOT NULL,
    identity_id     VARCHAR(64)  NOT NULL,
    role            VARCHAR(24)  NOT NULL,
    units           INTEGER      NOT NULL,
    position        INTEGER      NOT NULL,

    CONSTRAINT fk_pov_contribution_identity FOREIGN KEY (tenant_id, identity_id) REFERENCES pov_identity (tenant_id, id),
    CONSTRAINT uq_pov_contribution_position UNIQUE (value_event_id, position),
    CONSTRAINT chk_pov_contribution_role CHECK (role IN ('SPECIFIER', 'ARCHITECT', 'IMPLEMENTER', 'REVIEWER',
        'KNOWLEDGE_PROVIDER', 'COMPUTE_PROVIDER', 'OPERATOR', 'CAPITAL_PROVIDER')),
    CONSTRAINT chk_pov_contribution_units CHECK (units >= 0)
);

CREATE INDEX idx_pov_contribution_event    ON pov_contribution (value_event_id, position);
CREATE INDEX idx_pov_contribution_identity ON pov_contribution (tenant_id, identity_id);
