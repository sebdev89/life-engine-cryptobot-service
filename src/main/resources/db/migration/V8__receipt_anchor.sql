-- V8 — KAN-394: anchoring batches on Solana devnet (Endgame §11).
--
--   * receipt_anchor        — one row per Merkle root. The root is the identity: a dropped or reorged
--                             memo transaction is re-sent for the SAME root with the same memo and
--                             proofs (idempotent re-anchor); only tx/blockhash/attempts change.
--                             status: PENDING → SUBMITTED → FINALIZED | FAILED → (SUBMITTED again |
--                             ABANDONED after max attempts). Receipts are stamped (anchor_* columns of
--                             intelligence_receipt, V6) only at FINALIZED — never at confirmed.
--   * receipt_anchor_member — which receipts a root covers and the Merkle siblings ("L:sha256:…" /
--                             "R:sha256:…", leaf up) that lead each one to the root. Written with the
--                             anchor, before anything is signed, so a verifier can recompute the root
--                             from the members whatever the transaction did.
--
-- Nothing tenant-specific lives here: a batch spans tenants and carries only hashes. Per-tenant
-- reads join intelligence_receipt (owner_id). V7 belongs to KAN-392 (deterministic inference).

CREATE TABLE receipt_anchor (
    root                     VARCHAR(71)  PRIMARY KEY,
    chain                    VARCHAR(32)  NOT NULL,
    status                   VARCHAR(16)  NOT NULL,
    memo                     VARCHAR(160) NOT NULL,
    receipt_count            INTEGER      NOT NULL,
    fee_payer                VARCHAR(64),
    tx                       VARCHAR(128),
    slot                     BIGINT,
    blockhash                VARCHAR(64),
    last_valid_block_height  BIGINT,
    attempts                 INTEGER      NOT NULL DEFAULT 0,
    last_error               TEXT,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    submitted_at             TIMESTAMPTZ,
    finalized_at             TIMESTAMPTZ,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_receipt_anchor_root CHECK (root ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_receipt_anchor_count CHECK (receipt_count > 0),
    CONSTRAINT chk_receipt_anchor_status CHECK (status IN ('PENDING', 'SUBMITTED', 'FINALIZED', 'FAILED', 'ABANDONED'))
);

CREATE INDEX idx_receipt_anchor_status ON receipt_anchor (status, updated_at);
CREATE INDEX idx_receipt_anchor_created ON receipt_anchor (created_at DESC);

CREATE TABLE receipt_anchor_member (
    root          VARCHAR(71) NOT NULL REFERENCES receipt_anchor (root),
    receipt_hash  VARCHAR(71) NOT NULL REFERENCES intelligence_receipt (receipt_hash),
    proof         JSONB       NOT NULL DEFAULT '[]'::jsonb,

    PRIMARY KEY (root, receipt_hash)
);

CREATE INDEX idx_receipt_anchor_member_receipt ON receipt_anchor_member (receipt_hash);
