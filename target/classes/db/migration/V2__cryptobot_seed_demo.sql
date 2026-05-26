-- Phase-1 deterministic seed for the crypto.market-review.v1 happy path.
-- Same UUID namespace as the modulith's V82 seed so reruns and cross-system
-- inspection match. Only crypto-core symbols here — equities can be added in
-- a later migration if/when AI Compute / Semiconductors / etc. move over.

INSERT INTO watchlist_entry (
    id, symbol, display_name, asset_type, sector_theme, exchange, priority, active, notes, metadata_json, created_at, updated_at
)
VALUES
    ('f1eee000-0001-7001-8001-000000000001'::uuid, 'BTCUSDT', 'Bitcoin',  'CRYPTO', 'CRYPTO_CORE', 'BINANCE', 100, TRUE, NULL, '{}'::jsonb, NOW(), NOW()),
    ('f1eee000-0001-7001-8001-000000000002'::uuid, 'ETHUSDT', 'Ethereum', 'CRYPTO', 'CRYPTO_CORE', 'BINANCE', 100, TRUE, NULL, '{}'::jsonb, NOW(), NOW()),
    ('f1eee000-0001-7001-8001-000000000003'::uuid, 'SOLUSDT', 'Solana',   'CRYPTO', 'CRYPTO_CORE', 'BINANCE', 100, TRUE, NULL, '{}'::jsonb, NOW(), NOW())
ON CONFLICT (symbol, exchange) DO NOTHING;

-- Two demo zones for BTCUSDT so the LoadCryptoMarketContextAgent has something
-- to render on first boot. Confidence kept conservative; bounds are coarse
-- market-review brackets, not execution levels.
INSERT INTO price_zone (
    id, symbol, timeframe, zone_kind, lower_bound, upper_bound, confidence, valid_from, valid_until, label, metadata_json, created_at, updated_at
)
VALUES
    ('f1eee000-0001-7001-8002-000000000001'::uuid, 'BTCUSDT', '1h', 'SUPPORT',    60000.0, 62000.0, 0.6, NOW() - INTERVAL '7 days', NULL, 'Local demand shelf',          '{"source":"seed"}'::jsonb, NOW(), NOW()),
    ('f1eee000-0001-7001-8002-000000000002'::uuid, 'BTCUSDT', '1h', 'RESISTANCE', 70000.0, 72000.0, 0.55, NOW() - INTERVAL '7 days', NULL, 'Prior swing high cluster',    '{"source":"seed"}'::jsonb, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- One demo journal entry per seeded crypto symbol so the journal tool returns
-- non-empty output without depending on any prior user activity.
INSERT INTO trade_journal_entry (
    id, symbol, entry_time, title, body, sentiment, tags, linked_watchlist_id, metadata_json, created_at, updated_at
)
VALUES
    ('f1eee000-0001-7001-8003-000000000001'::uuid, 'BTCUSDT', NOW() - INTERVAL '2 days', 'Range trade hypothesis', 'Bias unchanged; waiting for break of 70k or rejection.', 'NEUTRAL', 'range,observation', 'f1eee000-0001-7001-8001-000000000001'::uuid, '{}'::jsonb, NOW(), NOW()),
    ('f1eee000-0001-7001-8003-000000000002'::uuid, 'ETHUSDT', NOW() - INTERVAL '3 days', 'Following BTC',          'ETH correlated; no independent signal yet.',            'NEUTRAL', 'correlation',      'f1eee000-0001-7001-8001-000000000002'::uuid, '{}'::jsonb, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- One indicator snapshot per crypto symbol so the indicators tool has data.
INSERT INTO indicator_snapshot (
    id, symbol, timeframe, indicator_name, period, computed_at, value_numeric, value_json, metadata_json, created_at
)
VALUES
    ('f1eee000-0001-7001-8004-000000000001'::uuid, 'BTCUSDT', '1h', 'RSI', 14, NOW() - INTERVAL '5 minutes', 52.3, NULL, '{"source":"seed"}'::jsonb, NOW()),
    ('f1eee000-0001-7001-8004-000000000002'::uuid, 'BTCUSDT', '1h', 'EMA', 50, NOW() - INTERVAL '5 minutes', 67450.0, NULL, '{"source":"seed"}'::jsonb, NOW()),
    ('f1eee000-0001-7001-8004-000000000003'::uuid, 'ETHUSDT', '1h', 'RSI', 14, NOW() - INTERVAL '5 minutes', 48.1, NULL, '{"source":"seed"}'::jsonb, NOW())
ON CONFLICT (id) DO NOTHING;

-- Two demo market observations for BTCUSDT so the observations tool has output
-- on a clean DB (the LoadCryptoMarketContextAgent prefers recent rows first).
INSERT INTO market_observation (
    id, symbol, venue, observed_at, timeframe, last_price, change_pct_24h, volume_quote_24h, spread_bps, liquidity_score, regime, metadata_json, created_at
)
VALUES
    ('f1eee000-0001-7001-8005-000000000001'::uuid, 'BTCUSDT', 'BINANCE', NOW() - INTERVAL '10 minutes', '1h', 67800.0, 1.2, 28500000000.0, 1.4, 0.92, 'RANGE', '{"source":"seed"}'::jsonb, NOW()),
    ('f1eee000-0001-7001-8005-000000000002'::uuid, 'BTCUSDT', 'BINANCE', NOW() - INTERVAL '1 hour',    '1h', 67320.0, 0.8, 28100000000.0, 1.5, 0.91, 'RANGE', '{"source":"seed"}'::jsonb, NOW())
ON CONFLICT (id) DO NOTHING;
