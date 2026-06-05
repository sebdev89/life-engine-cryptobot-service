-- V3 — local linkage row for one cryptobot market-review execution.
--
-- One row per `POST /api/cryptobot/market-review` and per monitoring tick.
-- Stores ONLY local linkage + reconciled summary metadata. The Runtime
-- (`life-engine-runtime`) remains the source of truth for workflow
-- execution and events; we reference it via `runtime_run_id`.
--
-- No paper trading, no orders, no PnL. This is read-only review history.

CREATE TABLE market_review_run (
    id                       UUID         PRIMARY KEY,
    symbol                   VARCHAR(32)  NOT NULL,
    runtime_run_id           UUID         NOT NULL UNIQUE,
    workflow_id              VARCHAR(64)  NOT NULL DEFAULT 'crypto.market-review.v1',
    status                   VARCHAR(16)  NOT NULL,
    requested_by             VARCHAR(128),
    started_at               TIMESTAMPTZ  NOT NULL,
    finished_at              TIMESTAMPTZ,
    verdict                  VARCHAR(16),
    summary                  TEXT,
    linked_journal_id        UUID         REFERENCES trade_journal_entry (id) ON DELETE SET NULL,
    linked_observation_id    UUID         REFERENCES market_observation (id)  ON DELETE SET NULL,
    metadata_json            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_market_review_run_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),

    CONSTRAINT chk_market_review_run_verdict
        CHECK (
            verdict IS NULL
            OR verdict IN ('BULLISH', 'BEARISH', 'NEUTRAL', 'UNKNOWN')
        )
);

CREATE INDEX idx_market_review_run_symbol_time
    ON market_review_run (symbol, started_at DESC);

CREATE INDEX idx_market_review_run_open
    ON market_review_run (status, started_at DESC)
    WHERE status IN ('PENDING', 'RUNNING');

-- Reuse the shared trigger function already installed in V1.
CREATE TRIGGER tr_market_review_run_updated_at
    BEFORE UPDATE ON market_review_run
    FOR EACH ROW
    EXECUTE PROCEDURE cryptobot_touch_updated_at();
