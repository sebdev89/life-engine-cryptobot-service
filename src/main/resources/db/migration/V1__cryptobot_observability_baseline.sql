-- cryptobot-service observability baseline.
-- Schema: public (matches the Phase-1 contract: a dedicated DB, single namespace).
-- Source-of-truth: derived from life-engine V82__crypto_observability_mvp_schema_and_seed.sql
-- (originally schema `crypto.*` in the modulith DB), flattened to `public.*` here.

-- ---------------------------------------------------------------------------
-- Shared touch helper for editable rows (watchlist, zones, journal). Append-only
-- tables (market_observation, indicator_snapshot) do not get the trigger.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION cryptobot_touch_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------
-- watchlist_entry: curated symbols for dashboards / journals.
-- ---------------------------------------------------------------------------
CREATE TABLE watchlist_entry (
    id              UUID PRIMARY KEY,
    symbol          VARCHAR(32)  NOT NULL,
    display_name    VARCHAR(128),
    asset_type      VARCHAR(32)  NOT NULL,
    sector_theme    VARCHAR(64),
    exchange        VARCHAR(64)  NOT NULL,
    priority        INT          NOT NULL DEFAULT 100,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    notes           TEXT,
    metadata_json   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_watchlist_asset_type
        CHECK (asset_type IN ('CRYPTO', 'EQUITY')),
    CONSTRAINT chk_watchlist_sector_theme
        CHECK (
            sector_theme IS NULL
            OR sector_theme IN (
                'CRYPTO_CORE',
                'AI_COMPUTE',
                'SEMICONDUCTOR_BOTTLENECK',
                'CLOUD_AI_PLATFORM',
                'NETWORKING_DATACENTER',
                'AI_ENERGY_INFRA'
            )
        ),
    CONSTRAINT uq_watchlist_symbol_exchange UNIQUE (symbol, exchange)
);

CREATE INDEX idx_watchlist_sector_symbol
    ON watchlist_entry (sector_theme, symbol);

CREATE INDEX idx_watchlist_active_priority
    ON watchlist_entry (active, priority)
    WHERE active = TRUE;

CREATE TRIGGER tr_watchlist_entry_updated_at
    BEFORE UPDATE ON watchlist_entry
    FOR EACH ROW
    EXECUTE PROCEDURE cryptobot_touch_updated_at();

-- ---------------------------------------------------------------------------
-- price_zone: qualitative support / resistance bands (no execution).
-- ---------------------------------------------------------------------------
CREATE TABLE price_zone (
    id              UUID PRIMARY KEY,
    symbol          VARCHAR(32)  NOT NULL,
    timeframe       VARCHAR(16)  NOT NULL,
    zone_kind       VARCHAR(32)  NOT NULL,
    lower_bound     NUMERIC(24, 8),
    upper_bound     NUMERIC(24, 8),
    confidence      NUMERIC(5, 4),
    valid_from      TIMESTAMPTZ  NOT NULL,
    valid_until     TIMESTAMPTZ,
    label           VARCHAR(128),
    metadata_json   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_price_zone_kind
        CHECK (zone_kind IN ('SUPPORT', 'RESISTANCE', 'RANGE', 'OTHER')),
    CONSTRAINT chk_price_zone_bounds
        CHECK (
            lower_bound IS NULL
            OR upper_bound IS NULL
            OR lower_bound <= upper_bound
        )
);

CREATE INDEX idx_price_zone_symbol_time
    ON price_zone (symbol, valid_from DESC);

CREATE TRIGGER tr_price_zone_updated_at
    BEFORE UPDATE ON price_zone
    FOR EACH ROW
    EXECUTE PROCEDURE cryptobot_touch_updated_at();

-- ---------------------------------------------------------------------------
-- market_observation: point-in-time venue snapshots (append-only).
-- ---------------------------------------------------------------------------
CREATE TABLE market_observation (
    id                  UUID PRIMARY KEY,
    symbol              VARCHAR(32)  NOT NULL,
    venue               VARCHAR(64)  NOT NULL,
    observed_at         TIMESTAMPTZ  NOT NULL,
    timeframe           VARCHAR(16),
    last_price          NUMERIC(24, 8),
    change_pct_24h      NUMERIC(16, 8),
    volume_quote_24h    NUMERIC(24, 8),
    spread_bps          NUMERIC(12, 4),
    liquidity_score     NUMERIC(8, 4),
    regime              VARCHAR(32),
    metadata_json       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_market_obs_regime
        CHECK (
            regime IS NULL
            OR regime IN ('TREND', 'RANGE', 'VOLATILE', 'UNKNOWN')
        )
);

CREATE INDEX idx_market_obs_symbol_time
    ON market_observation (symbol, observed_at DESC);

CREATE INDEX idx_market_obs_venue_time
    ON market_observation (venue, observed_at DESC);

-- ---------------------------------------------------------------------------
-- trade_journal_entry: qualitative notes / narrative (not orders or fills).
-- ---------------------------------------------------------------------------
CREATE TABLE trade_journal_entry (
    id                      UUID PRIMARY KEY,
    symbol                  VARCHAR(32)  NOT NULL,
    entry_time              TIMESTAMPTZ  NOT NULL,
    title                   VARCHAR(256) NOT NULL,
    body                    TEXT,
    sentiment               VARCHAR(16),
    tags                    TEXT,
    linked_watchlist_id     UUID REFERENCES watchlist_entry (id) ON DELETE SET NULL,
    metadata_json           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_journal_sentiment
        CHECK (
            sentiment IS NULL
            OR sentiment IN ('BULLISH', 'BEARISH', 'NEUTRAL', 'UNKNOWN')
        )
);

CREATE INDEX idx_journal_symbol_time
    ON trade_journal_entry (symbol, entry_time DESC);

CREATE INDEX idx_journal_watchlist
    ON trade_journal_entry (linked_watchlist_id)
    WHERE linked_watchlist_id IS NOT NULL;

CREATE TRIGGER tr_trade_journal_entry_updated_at
    BEFORE UPDATE ON trade_journal_entry
    FOR EACH ROW
    EXECUTE PROCEDURE cryptobot_touch_updated_at();

-- ---------------------------------------------------------------------------
-- indicator_snapshot: computed indicator values at a point in time (append-only).
-- ---------------------------------------------------------------------------
CREATE TABLE indicator_snapshot (
    id              UUID PRIMARY KEY,
    symbol          VARCHAR(32)  NOT NULL,
    timeframe       VARCHAR(16)  NOT NULL,
    indicator_name  VARCHAR(64)  NOT NULL,
    period          INT,
    computed_at     TIMESTAMPTZ  NOT NULL,
    value_numeric   NUMERIC(24, 8),
    value_json      JSONB,
    metadata_json   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_indicator_symbol_time
    ON indicator_snapshot (symbol, computed_at DESC);

CREATE INDEX idx_indicator_name_tf
    ON indicator_snapshot (indicator_name, timeframe, computed_at DESC);
