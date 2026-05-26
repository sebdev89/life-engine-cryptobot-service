# cryptobot-service

Vertical Spring Boot service that owns the **CryptoBot** product surface, fully
extracted from the (legacy) life-engine modulith. The service:

- Persists CryptoBot observability data in its own PostgreSQL database
  (`life_engine_cryptobot`, schema `public`).
- Talks to **Binance public REST** for live market snapshots (no API keys, no
  trading — public endpoints only). Falls back to a deterministic local provider
  when Binance is unreachable.
- Exposes `/api/cryptobot/*` REST endpoints for watchlist, price zones, market
  observations, trade journal, indicators, and market-review submissions.
- Calls `life-engine-runtime` to execute the `crypto.market-review.v1` workflow
  (the 5-agent LLM pipeline lives in the runtime, not here).

Live trading and signed exchange APIs are intentionally absent in Phase 1.

## Architecture

| Package | Role |
|---|---|
| `api` | HTTP controllers + DTOs for `/api/cryptobot/*` |
| `application` | Application services (Watchlist, PriceZones, MarketObservations, TradeJournal, Indicators, MarketReview) |
| `domain` | Plain Java records for the five observability resources |
| `infrastructure.persistence.r2dbc` | R2DBC `Row` types + `ReactiveCrudRepository` interfaces |
| `infrastructure.binance` | `BinancePublicClient` (24h ticker / public REST), properties, `WebClient` configuration |
| `infrastructure.snapshot` | `DeterministicLocalSnapshotProvider`, `BinancePublicSnapshotProvider`, snapshot SPI |
| `infrastructure.runtime` | `RuntimeClient` — WebClient against `life-engine-runtime` with JWT propagation |
| `security` | JWT validation, WebFlux security filter chain, CORS |
| `health` | Health/info endpoints |

### Database

The single Flyway baseline (`V1__cryptobot_observability_baseline.sql`) creates:

- `watchlist_entry`
- `price_zone`
- `market_observation`
- `trade_journal_entry`
- `indicator_snapshot`
- a `cryptobot_touch_updated_at()` trigger function used by all five tables

`V2__cryptobot_seed_demo.sql` seeds a small demo dataset (`BTCUSDT`, `ETHUSDT`).
Both run against the dedicated `life_engine_cryptobot` database (the legacy
modulith tables stay in `life_engine` as dead data until Phase 3 cleanup).

## Running locally

### Database

```bash
# Create the database once (uses default postgres credentials).
createdb life_engine_cryptobot
# Flyway runs automatically at startup.
```

### Service

```bash
# 1. life-engine-auth must be running (default :8081) and share JWT_SECRET.
# 2. life-engine-runtime must be running (default :8090) and share JWT_SECRET.

# Defaults: --spring.profiles.active=local, port 8091.
./mvnw spring-boot:run
```

Smoke (obtain `$TOKEN` from life-engine-auth first):

```bash
curl -fsS http://127.0.0.1:8091/api/cryptobot/health | jq .

curl -fsS -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8091/api/cryptobot/watchlist | jq .

curl -fsS -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8091/api/cryptobot/snapshots/BTCUSDT | jq .

curl -fsS -X POST http://127.0.0.1:8091/api/cryptobot/market-review \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"BTCUSDT"}' | jq .
```

The `POST /market-review` response includes the `runId` you can pass to the
runtime to watch the 5-agent workflow execute:

```bash
curl -fsS -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8090/api/runtime/runs/$RUN_ID" | jq .
```

## Configuration

Key properties (full list in `application.yml`):

| Key | Default | Notes |
|---|---|---|
| `spring.r2dbc.url` | `r2dbc:postgresql://localhost:5432/life_engine_cryptobot` | Reactive driver |
| `spring.flyway.url` | `jdbc:postgresql://localhost:5432/life_engine_cryptobot` | Migrations only |
| `cryptobot.binance.base-url` | `https://api.binance.com` | Public REST root |
| `cryptobot.binance.timeout` | `5s` | HTTP timeout |
| `lifeengine.security.jwt.secret` | env `JWT_SECRET` | Must match life-engine-auth |
| `cryptobot.runtime.base-url` | `http://localhost:8090` | Runtime endpoint |

## Tests

```bash
mvn test
```

Covers:

- REST controllers for watchlist / zones / observations / journal / indicators
  (R2DBC repos are stubbed via `StubRepositoriesConfiguration`).
- `MarketReviewControllerTest` — 401 unauthenticated, 200 + linked runtime run id
  with a valid `RUNTIME_OPERATOR` JWT (runtime is stubbed via MockWebServer).
- `WatchlistServiceTest`, `MarketObservationsServiceTest` — service-level rules
  (symbol normalization, limit clamping).
- `BinancePublicClientTest` — `MockWebServer`-driven happy path + 5xx fallback.

## Disabled / forbidden

- No exchange credentials are stored or accepted.
- No order placement or withdrawal endpoints exist.
- Only **public** Binance REST endpoints are called.

If you add a new `MarketSnapshotProvider` that requires signed credentials,
record it in a separate ADR — Phase 1 stays read-only and key-less.
