-- V13 — KAN-819 (+ KAN-820, KAN-821, KAN-823): Proof of Value V2–V4 + V6, attribution primitives.
--
-- V2 AgentIdentity: `pov_identity` already has wallet / owner_id / operator_id / created_at (V12). The rule
--    "an AGENT has a wallet" is enforced by the service (422), not by a CHECK: V1 rows of agents without a
--    wallet exist and stay readable; the service backfills the wallet once when it is posted again.
-- V3 KnowledgeAsset: what an outcome reused (rules, prompts, strategies…), content-addressed, with its creator.
-- V4 ComputeReceipt: what the outcome cost to compute, and who provided it. Cost is not value: it is recorded
--    next to the event and never enters the distribution of units.
-- V6 Contribution Units ledger: a read model over pov_contribution (no table).
--
-- Nothing new goes on-chain: the assets and compute receipts of an event are part of its canonical JSON,
-- so they are committed by the VALUE_EVENT receipt's output hash and by the Merkle root already anchored.

CREATE TABLE pov_knowledge_asset (
    tenant_id     VARCHAR(64)  NOT NULL,
    id            VARCHAR(80)  NOT NULL,
    version       INTEGER      NOT NULL,
    kind          VARCHAR(24)  NOT NULL,
    title         VARCHAR(200) NOT NULL,
    creator_id    VARCHAR(64)  NOT NULL,
    content_hash  VARCHAR(71)  NOT NULL,
    parent_ids    VARCHAR(80)[] NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    PRIMARY KEY (tenant_id, id),
    CONSTRAINT fk_pov_knowledge_asset_creator FOREIGN KEY (tenant_id, creator_id) REFERENCES pov_identity (tenant_id, id),
    CONSTRAINT chk_pov_knowledge_asset_version CHECK (version >= 1),
    CONSTRAINT chk_pov_knowledge_asset_hash CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_pov_knowledge_asset_kind CHECK (kind IN ('ARCHITECTURE', 'PROMPT', 'RULESET', 'DATASET', 'EVAL_SUITE',
        'STRATEGY', 'RUNBOOK', 'ALGORITHM', 'AGENT_CONFIG', 'DOMAIN_KNOWLEDGE'))
);

CREATE INDEX idx_pov_knowledge_asset_creator ON pov_knowledge_asset (tenant_id, creator_id);

-- Which assets an accepted outcome used. `position` keeps the request order (it is the canonical order).
CREATE TABLE pov_value_event_knowledge (
    value_event_id  UUID         NOT NULL REFERENCES pov_value_event (id),
    tenant_id       VARCHAR(64)  NOT NULL,
    asset_id        VARCHAR(80)  NOT NULL,
    position        INTEGER      NOT NULL,

    PRIMARY KEY (value_event_id, asset_id),
    CONSTRAINT fk_pov_value_event_knowledge_asset FOREIGN KEY (tenant_id, asset_id) REFERENCES pov_knowledge_asset (tenant_id, id),
    CONSTRAINT uq_pov_value_event_knowledge_position UNIQUE (value_event_id, position)
);

CREATE INDEX idx_pov_value_event_knowledge_asset ON pov_value_event_knowledge (tenant_id, asset_id);

-- What the outcome cost to compute. `provider_wallet` is denormalized on purpose: it is the wallet the
-- provider had when the receipt was recorded (and the one committed in the canonical event).
-- gpu_millis: GPU time in milliseconds (the canonical JSON is integers-only, RFC 8785 as used here).
CREATE TABLE pov_compute_receipt (
    id                        UUID         PRIMARY KEY,
    value_event_id            UUID         NOT NULL REFERENCES pov_value_event (id),
    tenant_id                 VARCHAR(64)  NOT NULL,
    position                  INTEGER      NOT NULL,
    provider_id               VARCHAR(64)  NOT NULL,
    provider_wallet           VARCHAR(64)  NOT NULL,
    node                      VARCHAR(120) NOT NULL,
    model                     VARCHAR(120) NOT NULL,
    input_tokens              BIGINT       NOT NULL,
    output_tokens             BIGINT       NOT NULL,
    gpu_millis                BIGINT       NOT NULL,
    estimated_cost_micro_usd  BIGINT       NOT NULL,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_pov_compute_receipt_provider FOREIGN KEY (tenant_id, provider_id) REFERENCES pov_identity (tenant_id, id),
    CONSTRAINT uq_pov_compute_receipt_position UNIQUE (value_event_id, position),
    CONSTRAINT chk_pov_compute_receipt_amounts CHECK (input_tokens >= 0 AND output_tokens >= 0 AND gpu_millis >= 0
        AND estimated_cost_micro_usd >= 0)
);

CREATE INDEX idx_pov_compute_receipt_event    ON pov_compute_receipt (value_event_id, position);
CREATE INDEX idx_pov_compute_receipt_provider ON pov_compute_receipt (tenant_id, provider_id);
