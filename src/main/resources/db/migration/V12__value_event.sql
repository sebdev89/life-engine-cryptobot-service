-- V12 — KAN-818: Proof of Value V1, the Value Event core.
--
-- A value event is a contribution plus the acceptance that made it value. It is stored twice on
-- purpose: as a signed `intelligence_receipt` of kind VALUE_EVENT (so the existing anchoring batch
-- puts its hash on Solana devnet and the Merkle proof already works), and here with the canonical
-- JSON and the columns the dashboard groups by. `receipt_hash` links the two; `value_event_hash`
-- (= receipt.output.hash) is what a verifier recomputes from `canonical`.
-- Nothing here is a prompt, a diff or a key: identifiers, labels and hashes.

ALTER TABLE intelligence_receipt DROP CONSTRAINT chk_intelligence_receipt_kind;
ALTER TABLE intelligence_receipt ADD CONSTRAINT chk_intelligence_receipt_kind CHECK (kind IN (
    'WALLET_SNAPSHOT', 'HUMAN_IDEA', 'MARKET_ANALYSIS', 'RISK_DECISION', 'STRATEGY',
    'SIMULATION', 'EXECUTION', 'PROJECT_ANALYSIS', 'VALUE_EVENT'));

CREATE TABLE value_event (
    value_event_hash  VARCHAR(71)  PRIMARY KEY,
    receipt_hash      VARCHAR(71)  NOT NULL UNIQUE REFERENCES intelligence_receipt (receipt_hash),
    tenant_id         VARCHAR(64)  NOT NULL,
    owner_id          UUID         NOT NULL,
    contributor_id    VARCHAR(160) NOT NULL,
    contributor_kind  VARCHAR(16)  NOT NULL,
    agent_id          VARCHAR(160),
    contribution_type VARCHAR(16)  NOT NULL,
    evidence_hash     VARCHAR(71)  NOT NULL,
    evidence_ref      VARCHAR(160) NOT NULL,
    acceptor_id       VARCHAR(160) NOT NULL,
    acceptance_method VARCHAR(160) NOT NULL,
    acceptance_hash   VARCHAR(71)  NOT NULL,
    canonical         TEXT         NOT NULL,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_value_event_hash CHECK (value_event_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_value_event_not_self_accepted CHECK (contributor_id <> acceptor_id),
    CONSTRAINT chk_value_event_type CHECK (contribution_type IN ('CODE', 'ANALYSIS', 'DATA', 'REVIEW', 'OPERATION'))
);

CREATE INDEX idx_value_event_owner       ON value_event (owner_id, occurred_at DESC);
CREATE INDEX idx_value_event_contributor ON value_event (tenant_id, contributor_id);
CREATE INDEX idx_value_event_evidence    ON value_event (evidence_hash);
