-- V6 — KAN-391: Decision Receipts (Endgame §6-7).
--
-- One signed, content-addressed receipt per step of the pipeline; the DAG is the pipeline.
--   * intelligence_receipt — receipt_hash = SHA-256("life-engine.cryptobot.receipt" ‖ 0x00 ‖ canonical)
--                            is the primary key: identical bodies collapse, the id proves integrity,
--                            and a parent cannot name a child (the child's hash does not exist yet).
--                            `body` is the canonical value tree (hashes, ids, versions, counts,
--                            timestamps — never a prompt, an answer or a key); `canonical` the exact
--                            bytes that were hashed; `signature` Ed25519 by the service key `key_id`.
--                            `nonce` is unique per tenant: a replayed step cannot mint a second receipt.
--                            The anchor columns are outside the hash and filled in later (memo batch).
--   * receipt_edge         — child → parent, both FK: an edge to a receipt that does not exist is refused.
--   * artifact             — the output of a receipt as a reusable, content-addressed thing; the bytes
--                            stay where the service keeps them (`storage_ref`), only the hash travels.
--
-- Tenancy: `tenant_id` is the tenancy key CryptoBot resolves server-side from the JWT (today the
-- owner user id, see V4); `owner_id` is the user. Every query is scoped by one or the other.

CREATE TABLE intelligence_receipt (
    receipt_hash     VARCHAR(71)  PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    owner_id         UUID         NOT NULL,
    kind             VARCHAR(32)  NOT NULL,
    schema_version   VARCHAR(16)  NOT NULL,
    nonce            VARCHAR(160) NOT NULL,
    wallet_id        UUID,
    proposal_id      UUID,
    body             JSONB        NOT NULL,
    canonical        BYTEA        NOT NULL,
    signature        BYTEA        NOT NULL,
    key_id           VARCHAR(64)  NOT NULL,
    reproducibility  VARCHAR(24)  NOT NULL,
    anchor_chain     VARCHAR(32),
    anchor_tx        VARCHAR(128),
    anchor_slot      BIGINT,
    anchor_root      VARCHAR(71),
    anchor_proof     JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_intelligence_receipt_nonce UNIQUE (tenant_id, nonce),
    CONSTRAINT chk_intelligence_receipt_hash CHECK (receipt_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_intelligence_receipt_kind CHECK (kind IN (
        'WALLET_SNAPSHOT', 'HUMAN_IDEA', 'MARKET_ANALYSIS', 'RISK_DECISION', 'STRATEGY',
        'SIMULATION', 'EXECUTION', 'PROJECT_ANALYSIS')),
    CONSTRAINT chk_intelligence_receipt_level CHECK (reproducibility IN (
        'L0_SIGNED', 'L1_REPRODUCIBLE', 'L2_CHALLENGED', 'L3_PROVEN'))
);

CREATE INDEX idx_intelligence_receipt_wallet   ON intelligence_receipt (wallet_id, created_at DESC);
CREATE INDEX idx_intelligence_receipt_proposal ON intelligence_receipt (proposal_id, created_at);
CREATE INDEX idx_intelligence_receipt_tenant   ON intelligence_receipt (tenant_id, created_at DESC);
-- The anchoring batch's work list.
CREATE INDEX idx_intelligence_receipt_unanchored ON intelligence_receipt (created_at) WHERE anchor_tx IS NULL;

CREATE TABLE receipt_edge (
    child_hash   VARCHAR(71) NOT NULL REFERENCES intelligence_receipt (receipt_hash),
    parent_hash  VARCHAR(71) NOT NULL REFERENCES intelligence_receipt (receipt_hash),
    role         VARCHAR(16) NOT NULL DEFAULT 'DERIVES_FROM',

    PRIMARY KEY (child_hash, parent_hash),
    CONSTRAINT chk_receipt_edge_not_self CHECK (child_hash <> parent_hash),
    CONSTRAINT chk_receipt_edge_role CHECK (role IN ('DERIVES_FROM', 'VALIDATES', 'EXECUTES', 'REUSES'))
);

CREATE INDEX idx_receipt_edge_parent ON receipt_edge (parent_hash);

CREATE TABLE artifact (
    artifact_hash  VARCHAR(71)  PRIMARY KEY,
    receipt_hash   VARCHAR(71)  NOT NULL REFERENCES intelligence_receipt (receipt_hash),
    tenant_id      VARCHAR(64)  NOT NULL,
    type           VARCHAR(32)  NOT NULL,
    schema         VARCHAR(64),
    storage_ref    VARCHAR(160),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_artifact_hash CHECK (artifact_hash ~ '^sha256:[0-9a-f]{64}$')
);

CREATE INDEX idx_artifact_receipt ON artifact (receipt_hash);
CREATE INDEX idx_artifact_tenant  ON artifact (tenant_id, created_at DESC);
