-- V14 — KAN-822: Proof of Value V5, immediate reward (payouts in devnet SOL).
--
-- An ANCHORED ValueEvent can distribute a predefined pool (cryptobot.pov.reward.pool-lamports) to the wallets of its
-- contributors: lamports = floor(units / totalUnits × pool) per identity, one SystemProgram.transfer per destination,
-- each one attested by the independent validator and signed by the isolated signer like any execution. A contributor
-- without a wallet is recorded UNFUNDED (not paid, nothing invented). The result is one more Decision Receipt:
-- VALUE_DISTRIBUTION, child of the VALUE_EVENT receipt, anchored by the same sweep.
--
-- Tenancy: the same key as the rest (`tenant_id` = the JWT owner, resolved server-side). No secrets: ids, public keys,
-- amounts, signatures and error texts written by the service (never a token or a key).

ALTER TABLE intelligence_receipt DROP CONSTRAINT chk_intelligence_receipt_kind;
ALTER TABLE intelligence_receipt ADD CONSTRAINT chk_intelligence_receipt_kind CHECK (kind IN (
    'WALLET_SNAPSHOT', 'HUMAN_IDEA', 'MARKET_ANALYSIS', 'RISK_DECISION', 'STRATEGY',
    'SIMULATION', 'EXECUTION', 'PROJECT_ANALYSIS', 'VALUE_EVENT', 'VALUE_DISTRIBUTION'));

-- One distribution per event (the idempotency key of POST /value-events/{id}/distribute).
CREATE TABLE pov_distribution (
    id              UUID         PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    value_event_id  UUID         NOT NULL REFERENCES pov_value_event (id),
    pool_lamports   BIGINT       NOT NULL,
    policy          VARCHAR(64)  NOT NULL,
    receipt_hash    VARCHAR(71)  REFERENCES intelligence_receipt (receipt_hash),
    status          VARCHAR(16)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_pov_distribution_event UNIQUE (value_event_id),
    CONSTRAINT chk_pov_distribution_pool CHECK (pool_lamports > 0),
    CONSTRAINT chk_pov_distribution_status CHECK (status IN ('IN_PROGRESS', 'PARTIAL', 'COMPLETE', 'FAILED'))
);

CREATE INDEX idx_pov_distribution_tenant ON pov_distribution (tenant_id, created_at DESC);

-- One payout per contributing identity (its units of every role added up: one transaction per destination).
CREATE TABLE pov_payout (
    id               UUID         PRIMARY KEY,
    distribution_id  UUID         NOT NULL REFERENCES pov_distribution (id),
    value_event_id   UUID         NOT NULL REFERENCES pov_value_event (id),
    tenant_id        VARCHAR(64)  NOT NULL,
    position         INTEGER      NOT NULL,
    identity_id      VARCHAR(64)  NOT NULL,
    wallet           VARCHAR(64),
    lamports         BIGINT       NOT NULL,
    status           VARCHAR(12)  NOT NULL,
    tx_signature     VARCHAR(100),
    explorer_url     VARCHAR(400),
    error            VARCHAR(500),
    policy           VARCHAR(64)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_pov_payout_identity FOREIGN KEY (tenant_id, identity_id) REFERENCES pov_identity (tenant_id, id),
    CONSTRAINT uq_pov_payout_position UNIQUE (distribution_id, position),
    CONSTRAINT uq_pov_payout_identity UNIQUE (distribution_id, identity_id),
    CONSTRAINT chk_pov_payout_status CHECK (status IN ('PENDING', 'SUBMITTED', 'CONFIRMED', 'FAILED', 'UNFUNDED')),
    CONSTRAINT chk_pov_payout_lamports CHECK (lamports >= 0)
);

CREATE INDEX idx_pov_payout_identity ON pov_payout (tenant_id, identity_id);
CREATE INDEX idx_pov_payout_recent   ON pov_payout (tenant_id, status, updated_at);
