-- V15 — KAN-824 / KAN-825: Proof of Value V7 RevenueEvent (and the V8 Treasury read model, which needs no table of its own).
--
-- A RevenueEvent records an economic result (in the demo: SIMULATED, labelled so — never presented as real profit) and
-- splits it with the fixed, auditable policy pov/revenue-share/v1: contributor pool = floor(amount × share_bps / 10 000),
-- protocol fee = floor(amount × fee_bps / 10 000) (only recorded), retained = the rest. The pool is split among EVERY
-- contribution of the linked ANCHORED ValueEvents, pro rata to their units (floor; the dust stays retained), and paid with
-- the V5 flow: one attested + signed devnet SOL transfer per wallet, rows in pov_payout (revenue_event_id instead of
-- value_event_id), UNFUNDED without a wallet. The result is one more Decision Receipt: REVENUE_EVENT, child of the linked
-- VALUE_EVENT receipts, anchored by the same sweep.
--
-- Tenancy: the same key as the rest (`tenant_id` = the JWT owner, resolved server-side). No secrets: ids, public keys,
-- amounts, signatures and error texts written by the service.

ALTER TABLE intelligence_receipt DROP CONSTRAINT chk_intelligence_receipt_kind;
ALTER TABLE intelligence_receipt ADD CONSTRAINT chk_intelligence_receipt_kind CHECK (kind IN (
    'WALLET_SNAPSHOT', 'HUMAN_IDEA', 'MARKET_ANALYSIS', 'RISK_DECISION', 'STRATEGY',
    'SIMULATION', 'EXECUTION', 'PROJECT_ANALYSIS', 'VALUE_EVENT', 'VALUE_DISTRIBUTION', 'REVENUE_EVENT'));

CREATE TABLE pov_revenue_event (
    id                         UUID         PRIMARY KEY,
    tenant_id                  VARCHAR(64)  NOT NULL,
    project_id                 VARCHAR(64)  NOT NULL,
    source_kind                VARCHAR(16)  NOT NULL,
    source_ref                 VARCHAR(200) NOT NULL,
    simulated                  BOOLEAN      NOT NULL,
    amount_lamports            BIGINT       NOT NULL,
    attribution_policy         VARCHAR(64)  NOT NULL,
    revenue_share_bps          INTEGER      NOT NULL,
    protocol_fee_bps           INTEGER      NOT NULL,
    contributor_pool_lamports  BIGINT       NOT NULL,
    protocol_fee_lamports      BIGINT       NOT NULL,
    retained_lamports          BIGINT       NOT NULL,
    -- The agent whose (accounting) treasury earned it: cryptobot.pov.revenue.treasury-identity-id when it was recorded.
    treasury_identity_id       VARCHAR(64),
    receipt_hash               VARCHAR(71)  REFERENCES intelligence_receipt (receipt_hash),
    status                     VARCHAR(16)  NOT NULL,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- One economic result per source: the idempotency key of POST /revenue-events (a proposal is never counted twice).
    CONSTRAINT uq_pov_revenue_source UNIQUE (tenant_id, source_kind, source_ref),
    CONSTRAINT chk_pov_revenue_source_kind CHECK (source_kind IN ('PROPOSAL', 'SIMULATED', 'EXTERNAL')),
    CONSTRAINT chk_pov_revenue_simulated CHECK (source_kind <> 'SIMULATED' OR simulated),
    CONSTRAINT chk_pov_revenue_amount CHECK (amount_lamports > 0),
    CONSTRAINT chk_pov_revenue_bps CHECK (revenue_share_bps >= 0 AND protocol_fee_bps >= 0 AND revenue_share_bps + protocol_fee_bps <= 10000),
    CONSTRAINT chk_pov_revenue_split CHECK (contributor_pool_lamports >= 0 AND protocol_fee_lamports >= 0 AND retained_lamports >= 0
        AND contributor_pool_lamports + protocol_fee_lamports + retained_lamports = amount_lamports),
    CONSTRAINT chk_pov_revenue_status CHECK (status IN ('IN_PROGRESS', 'PARTIAL', 'COMPLETE', 'FAILED'))
);

CREATE INDEX idx_pov_revenue_tenant ON pov_revenue_event (tenant_id, created_at DESC);
CREATE INDEX idx_pov_revenue_treasury ON pov_revenue_event (tenant_id, treasury_identity_id);

-- The ValueEvents a RevenueEvent is attributed to, and the lamports their contributions received from it.
CREATE TABLE pov_revenue_link (
    revenue_event_id  UUID     NOT NULL REFERENCES pov_revenue_event (id),
    value_event_id    UUID     NOT NULL REFERENCES pov_value_event (id),
    position          INTEGER  NOT NULL,
    share_lamports    BIGINT   NOT NULL,

    PRIMARY KEY (revenue_event_id, value_event_id),
    CONSTRAINT uq_pov_revenue_link_position UNIQUE (revenue_event_id, position),
    CONSTRAINT chk_pov_revenue_link_share CHECK (share_lamports >= 0)
);

CREATE INDEX idx_pov_revenue_link_value_event ON pov_revenue_link (value_event_id);

-- Revenue payouts reuse pov_payout: exactly one of value_event_id (a V5 distribution) / revenue_event_id.
ALTER TABLE pov_payout ALTER COLUMN distribution_id DROP NOT NULL;
ALTER TABLE pov_payout ALTER COLUMN value_event_id DROP NOT NULL;
ALTER TABLE pov_payout ADD COLUMN revenue_event_id UUID REFERENCES pov_revenue_event (id);
ALTER TABLE pov_payout ADD CONSTRAINT chk_pov_payout_source CHECK ((value_event_id IS NULL) <> (revenue_event_id IS NULL));
ALTER TABLE pov_payout ADD CONSTRAINT chk_pov_payout_distribution CHECK ((distribution_id IS NULL) = (value_event_id IS NULL));
ALTER TABLE pov_payout ADD CONSTRAINT uq_pov_payout_revenue_position UNIQUE (revenue_event_id, position);
ALTER TABLE pov_payout ADD CONSTRAINT uq_pov_payout_revenue_identity UNIQUE (revenue_event_id, identity_id);
