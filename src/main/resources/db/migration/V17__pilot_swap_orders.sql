-- V17 — KAN-1249: the pilot's swap orders. A swap is proposed (quoted, built, checked and simulated: nothing signed),
-- approved by the one configured human approver, and only then executed. Every order binds exactly what was shown
-- to the approver — direction, cluster, input amount, minimum output and the hash of the preview — so an approval can
-- never be reused for a different swap. Service-level, like paper: the pilot's own book, not a tenant's.

CREATE TABLE swap_order (
    id                UUID          PRIMARY KEY,
    -- the creator's Idempotency-Key: the same key returns the same order instead of a second one
    idempotency_key   VARCHAR(100)  NOT NULL UNIQUE,
    created_by        UUID          NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    direction         VARCHAR(10)   NOT NULL,
    cluster           VARCHAR(20)   NOT NULL,
    owner             VARCHAR(64)   NOT NULL,
    in_amount         BIGINT        NOT NULL,
    min_out           BIGINT        NOT NULL,
    max_slippage_bps  INTEGER       NOT NULL,
    -- the quote, the unsigned transaction and the simulation the approver saw, and its SHA-256
    preview           TEXT          NOT NULL,
    preview_hash      CHAR(64)      NOT NULL,
    -- CSRF token of the approval, bound to this order and readable only by the approver
    approval_nonce    CHAR(64)      NOT NULL,
    approved_by       UUID,
    approved_at       TIMESTAMPTZ,
    approval_jti      VARCHAR(100)  UNIQUE,
    expires_at        TIMESTAMPTZ   NOT NULL,
    tx_signature      VARCHAR(100),
    error             VARCHAR(500),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_swap_order_status CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'EXECUTING', 'SENT', 'CONFIRMED',
                                                       'UNKNOWN_SENT', 'FAILED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT chk_swap_order_direction CHECK (direction IN ('SOL_USDC', 'USDC_SOL')),
    CONSTRAINT chk_swap_order_cluster CHECK (cluster IN ('devnet', 'mainnet-beta')),
    CONSTRAINT chk_swap_order_amounts CHECK (in_amount > 0 AND min_out > 0 AND max_slippage_bps BETWEEN 0 AND 10000),
    CONSTRAINT chk_swap_order_approval CHECK ((approved_by IS NULL) = (approved_at IS NULL))
);

CREATE INDEX idx_swap_order_status ON swap_order (status);

-- Append-only audit: who did what to which order, when, against which preview.
CREATE TABLE swap_order_audit (
    id            BIGSERIAL     PRIMARY KEY,
    order_id      UUID          NOT NULL REFERENCES swap_order (id),
    event         VARCHAR(30)   NOT NULL,
    actor         UUID,
    preview_hash  CHAR(64),
    detail        VARCHAR(500),
    at            TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_swap_order_audit_order ON swap_order_audit (order_id, at);
