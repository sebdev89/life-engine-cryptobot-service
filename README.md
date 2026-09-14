# CryptoBot — AI control plane for Solana wallets

> Colosseum · Crypto World's Fair 2026 · Solana track.
> **The agent proposes. You approve. An isolated signer executes — on devnet, under policy, with a
> full audit trail.** Built on Life Engine (Auth · Runtime · observability); this repo holds only
> the crypto domain.

```
wallet → portfolio → risk detection → AI analysis → rebalance proposal
      → economic + on-chain simulation → policy → human approval → devnet execution → audit trail
```

## What it does today

| Step | What happens | Where |
|---|---|---|
| Track a wallet | Any public Solana address (devnet, or mainnet read-only). SOL + SPL/Token-2022 balances, recent signatures. | `adapters/solana/SolanaRpcClient` |
| Value it | Jupiter Price v3 by mint (keyless), labelled fallback when the oracle is down. Devnet mints are valued as the mainnet asset they represent. | `adapters/marketdata` |
| Detect risk | Deterministic rules: concentration (> 60 % HIGH, > 40 % MEDIUM), no stablecoin buffer, dust, unpriced tokens, sharp move since the last snapshot. | `application/controlplane/RiskEngine` |
| Ask in natural language | *"¿Cuál es mi mayor riesgo?"* → Life Engine Runtime workflow `crypto.portfolio-advisor.v1` (one LLM stage, strict JSON). The model sees positions, weights and findings — **never a key, never a transaction**. | `AdvisorService` · runtime `ext/cryptomarketreview/portfolio` |
| Propose | *"SOL 70 % → 50 %"* → planner computes the legs; the LLM never sets amounts. | `RebalancePlanner` |
| Simulate | Economic (spot × amount, fee) **and** on-chain: the exact unsigned transaction goes through `simulateTransaction` (`sigVerify=false`, so read-only wallets simulate too). | `SimulationService` |
| Policy | Kill switch · asset allowlist · max USD · max % of portfolio · cooldown · devnet only · lamport cap · vault configured · simulation passed · signer controls the wallet. Each rule is named in the audit trail. | `PolicyEngine` |
| Approve | Explicit human decision, recorded with who/when/note. `execute` before `APPROVED` is a 409. | `ProposalService` |
| Execute (devnet) | Second explicit click. Re-simulates on a fresh blockhash, asks the **isolated signer** (separate process, own secret) to sign, verifies the signature against the wallet key, broadcasts, confirms, links the explorer. | `ExecutionService` · `signer/` |
| Audit | Append-only `audit_event` per transition: created, simulated, policy evaluated, awaiting approval, approved/rejected, submitted, executed/failed. | `AuditService` |

Legacy (pre-hackathon, still available, not part of the demo): Binance-public watchlist / price
zones / journal / indicators and the 5-agent `crypto.market-review.v1` — now also fed by Solana via
`SolanaSnapshotProvider` (`cryptobot.snapshot.provider=solana-public`, GeckoTerminal pool + Jupiter).

## Why this and not a crypto chatbot

A chatbot talks. This acts **inside a policy**: every proposal is simulated against the real chain
before a human sees it, the human decision is a persisted record, and the only component that can
sign is a separate process that refuses anything but an allow-listed transfer under a cap. The LLM
cannot skip a rule because it never touches the pipeline after "suggest".

## Run it locally (3 processes + Life Engine dev stack)

Prerequisites: Java 21, Maven, Node 24, Postgres `:5433` (`life_engine_cryptobot`), Life Engine
Auth `:8081` and Runtime `:8090` running (`scripts/dev-up.sh` in the workspace), Ollama with the
Runtime's `chat` role model.

```bash
# 1. signer — the only process with a key (generate one: see signer/README.md)
export SIGNER_KEYPAIR_PATH=~/.cryptobot-demo/demo-wallet.json SIGNER_TOKEN=<service token>
export SIGNER_ALLOWED_DESTINATIONS=<rebalance vault pubkey>
mvn -f signer/pom.xml spring-boot:run                                   # :8096

# 2. service
export JWT_SECRET=<same as auth/runtime> AUTH_JWKS_URI=http://127.0.0.1:8081/.well-known/jwks.json
export CRYPTOBOT_REBALANCE_VAULT=<rebalance vault pubkey>
export CRYPTOBOT_SIGNER_ENABLED=true CRYPTOBOT_SIGNER_TOKEN=<service token>
export CRYPTOBOT_ADVISOR_LOCALE=es
./mvnw spring-boot:run                                                  # :8091

# 3. UI
cd ../cryptobot-ui && npm ci && npx ng serve --port 4204               # http://localhost:4204
```

Or, with Docker (service + signer + UI; Auth/Runtime stay on the host):

```bash
cp .env.hackathon.example .env.hackathon   # fill the secrets/addresses
docker compose -f docker-compose.hackathon.yml --env-file .env.hackathon up -d --build
open http://localhost:4204
```

Devnet SOL for the demo wallet: https://faucet.solana.com (the RPC airdrop is rate-limited).

## API

All endpoints take `Authorization: Bearer <Life Engine JWT>`. Everything is scoped by the token's
`sub` server-side; a foreign id is a 404.

| Method · path | Purpose |
|---|---|
| `POST /api/cryptobot/wallets` `{address, cluster?, label?}` | track a wallet, returns portfolio + risk |
| `GET /api/cryptobot/wallets` · `GET …/{id}/portfolio` · `POST …/{id}/refresh` · `GET …/{id}/activity` | read |
| `POST /api/cryptobot/wallets/{id}/ask` `{question}` | advisor answer + `runtimeRunId` + SSE path |
| `POST /api/cryptobot/wallets/{id}/proposals` `{targetWeights:{SOL:50}}` | plan → simulate → policy → `AWAITING_APPROVAL` / `BLOCKED_BY_POLICY` |
| `POST /api/cryptobot/proposals/{id}/approve` · `/reject` · `/execute` | human decision · devnet execution |
| `GET /api/cryptobot/proposals/{id}` · `/audit` | proposal with its trail |

## Security model

- No private key in this service, in the database, in the LLM input, or in a log. The signer
  loads a 64-byte `id.json` from a path/env and exposes only its public key.
- The LLM receives computed facts and returns suggestions. Amounts, simulation, policy and
  execution are deterministic code. A pasted private key in a question is rejected before any
  network call (`SECRET_IN_QUESTION`).
- Two independent emergency stops: `CRYPTOBOT_EXECUTION_ENABLED=false` (service) and
  `SIGNER_ENABLED=false` (signer).
- Execution is devnet-only by configuration (`cryptobot.policy.execution-cluster`), not by
  convention; mainnet wallets are read-only paper trades.
- Tenant = the JWT subject, resolved server-side. No client-supplied tenant header exists.

## Tests

```bash
./mvnw test                     # 114 tests: adapters (recorded responses), engines, state machine, HTTP flow with fake RPC + Runtime
./mvnw -f signer/pom.xml test   # 10 tests: signing policy (every refusal reason), token, signature verification
cd ../cryptobot-ui && npx ng test
```

## What existed before the hackathon vs. what was built during it

**Pre-existing (before 2026-09-14):** Spring Boot WebFlux skeleton, JWT/JWKS security against
Life Engine Auth, `RuntimeClient`, R2DBC + Flyway (`V1`–`V3`), build identity, CI workflow,
Binance-public market data, watchlist / zones / observations / journal / indicators, the
`crypto.market-review.v1` pipeline in the Runtime, and the Angular login/session shell.

**Built during the hackathon (from 2026-09-14):** everything under `adapters/`, `domain/`
(wallet, portfolio, risk, strategy, policy, transactions, advisor), `application/controlplane/`,
`integration/`, `infrastructure/persistence/controlplane/`, `api/controlplane/`, migration `V4`,
`infrastructure/solana` + `SolanaSnapshotProvider`, the `signer/` module, the Runtime module
`ext/cryptomarketreview/portfolio` (`crypto.portfolio-advisor.v1`), the control-plane UI, this
README and the Docker files. The exact list lives in the vault:
`Products/CryptoBot-Colosseum/06-Preexistente-vs-Hackathon.md`.

## Configuration

Full list with defaults in `src/main/resources/application.yml` under `cryptobot.solana`,
`cryptobot.marketdata`, `cryptobot.risk`, `cryptobot.policy`, `cryptobot.signer`,
`cryptobot.advisor`. Nothing secret has a default.
