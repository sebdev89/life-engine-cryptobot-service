-- V16 — KAN-1217: CryptoBot v3 paper trading. Strategies run forward on live closed SOL/USD candles with simulated
-- fills (the same Execution model as the v2 backtester): no wallet, no signer, no chain, no money. Every decision is
-- stored with its price, cost and resulting holdings so the 2–4 week verdict comes from rows, not from a session.
--
-- Service-level, not tenant-scoped: the portfolios are the service's own experiment, defined in configuration.
-- `params` freezes the strategy parameters at the first tick; the job refuses to continue a portfolio whose configured
-- parameters differ (changing knobs mid-window would be fitting the window).

CREATE TABLE paper_portfolio (
    id              VARCHAR(64)    PRIMARY KEY,
    strategy        VARCHAR(32)    NOT NULL,
    params          VARCHAR(500)   NOT NULL,
    fee_bps         INTEGER        NOT NULL,
    slippage_bps    INTEGER        NOT NULL,
    initial_usd     NUMERIC(20, 6) NOT NULL,
    sol             NUMERIC(30, 9) NOT NULL,
    usdc            NUMERIC(20, 6) NOT NULL,
    ticks           INTEGER        NOT NULL,
    trades          INTEGER        NOT NULL,
    costs_usd       NUMERIC(20, 6) NOT NULL,
    started_at      TIMESTAMPTZ    NOT NULL,
    last_bar_time   TIMESTAMPTZ    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_paper_portfolio_holdings CHECK (sol >= 0 AND usdc >= 0 AND initial_usd > 0)
);

-- One row per portfolio per closed candle: the decision and the state after it. The primary key makes a tick
-- idempotent — a restarted job cannot count the same candle twice.
CREATE TABLE paper_tick (
    portfolio_id    VARCHAR(64)    NOT NULL REFERENCES paper_portfolio (id),
    bar_time        TIMESTAMPTZ    NOT NULL,
    close           NUMERIC(20, 8) NOT NULL,
    notional_usd    NUMERIC(20, 6) NOT NULL,
    traded          BOOLEAN        NOT NULL,
    cost_usd        NUMERIC(20, 6) NOT NULL,
    sol             NUMERIC(30, 9) NOT NULL,
    usdc            NUMERIC(20, 6) NOT NULL,
    equity_usd      NUMERIC(20, 6) NOT NULL,
    sol_weight      NUMERIC(12, 10) NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (portfolio_id, bar_time)
);

-- Coinbase spot (the venue whose candles the strategies trade at) next to Jupiter's on-chain price, both read at
-- observed_at when a candle was processed: how far the simulated venue is from where a real Solana swap would price.
-- Same instant on purpose, so the gap is venue vs venue and not price drift since the close. Null = no answer.
CREATE TABLE paper_price_check (
    bar_time        TIMESTAMPTZ    PRIMARY KEY,
    coinbase_spot   NUMERIC(20, 8),
    jupiter_price   NUMERIC(20, 8),
    deviation_bps   INTEGER,
    observed_at     TIMESTAMPTZ    NOT NULL
);
