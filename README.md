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
| Deterministic authorization (KAN-436) | Versioned policy `R_v` (integers only, `H_R = SHA-256` of its canonical JSON) evaluated as a pure function over `(I, S)`: 11 predicates (`Valid(I) = ∧ Pᵢ`, unknown ⇒ deny) then a tier by trade value → **ALLOW / ESCALATE(second agent \| human signature) / DENY**. The verdict, `H_R`, the input hash and the verdict hash travel with the proposal and the `POLICY_EVALUATED` audit event; execution refuses a proposal decided under another `H_R`. | `domain/policy/DeterministicPolicyEngine` |
| Approve | Explicit human decision, recorded with who/when/note. `execute` before `APPROVED` is a 409. | `ProposalService` |
| Execute (devnet) | Second explicit click. Re-simulates on a fresh blockhash, asks the **isolated signer** (separate process, own secret) to sign, verifies the signature against the wallet key, broadcasts, confirms, links the explorer. | `ExecutionService` · `signer/` |
| Audit | Append-only `audit_event` per transition: created, simulated, policy evaluated, awaiting approval, approved/rejected, started, signed, submitted, executed/failed, reconciled. | `AuditService` |
| On-chain authority (KAN-437, paper level 4) | `programs/intent-authority`: a Solana program (devnet target) that refuses an execution unless the **agent signed** and its policy PDA is registered and not revoked (I1), the claimed `H_R` equals the one **committed on-chain** (I4), the current slot is within `valid_until_slot` (I2), and neither the receipt PDA of `H_I` nor the nonce PDA of `(agent, nonce)` exists (I3) — then leaves a receipt account atomically. Java client (PDAs, instructions, account decoders) byte-exact with the program via shared SDK vectors. Not yet wired into `ExecutionService`, not yet deployed (needs the Solana CLI: human step). | `programs/intent-authority` · `adapters/solana/authority` |
| Reliable execution (KAN-403) | `operationId` idempotency key bound **before** signing; optimistic version + status guard on every write; signature persisted **before** broadcast; `EXECUTING`/`SUBMITTED` rows reconciled against `getSignatureStatuses` at startup and every 30 s — never re-sent; transactional outbox (`trade.*` events) with `SKIP LOCKED` worker, backoff and dead-letter queue. | `ExecutionService` · `ReconciliationService` · `OutboxPublisher` |

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

# 2. service — JWT_SECRET (≥32 bytes) or AUTH_JWKS_URI is REQUIRED: since KAN-350 no profile
#    ships a default secret; without either, the service refuses to start.
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
| `POST /api/cryptobot/proposals/{id}/approve` · `/reject` | human decision |
| `POST /api/cryptobot/proposals/{id}/execute` (header `Idempotency-Key: <uuid>` or body `{operationId}`) | devnet execution. Same key ⇒ same result, never a second transaction; different key while `EXECUTING`/`SUBMITTED` ⇒ 409 (KAN-403) |
| `GET /api/cryptobot/proposals/{id}` · `/audit` · `/events` | proposal with its trail · durable `trade.*` events (outbox, with delivery state) and dead letters |
| `GET /api/cryptobot/quotes/{asset}?ars=<monto>&network=<red>` · `?side=SELL&amount=<unidades>` | ARS quotes across Argentine exchanges, ranked "recibís X" (KAN-355) |

### ARS quotes across exchanges (KAN-355)

"Con estos pesos, ¿dónde conviene comprar?" — the same table https://criptos.com.ar shows, computed
from each exchange's **public, keyless, read-only** price feed. criptos.com.ar itself is only a
manual validation oracle in development (its API is neither public nor documented); it is never
called from this code.

| Exchange | Feed | Fees on the feed |
|---|---|---|
| `bitso` | `GET https://api.bitso.com/v3/ticker/` (documented, docs.bitso.com) — `*_ars` books | no (`/v3/fees/` is authenticated) |
| `ripio` | `GET https://app.ripio.com/api/v3/rates/?country=AR` — `buy_rate`/`sell_rate` | no |
| `buenbit` | `GET https://be.buenbit.com/api/market/tickers/` — `purchase_price`/`selling_price` | no |

Port `ArsQuotesPort` → `CachedArsQuotesService` (one board per exchange, cached
`cryptobot.quotes.cache-ttl` = 45 s; a failed refresh serves the previous board flagged
`stale=true` up to `stale-max` = 5 min, then the exchange is `FETCH_FAILED`). Adapters implement
`ExchangeQuoteSource`; adding an exchange is one class + one fixture. Runtime never calls
exchanges: the advisor receives this ranking as context from the vertical.

```bash
# buy: 100 000 ARS of USDT withdrawn over TRON → USDT received per exchange, best first
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8091/api/cryptobot/quotes/USDT?ars=100000&network=TRON'
# sell: 0.01 BTC → ARS received per exchange, best first
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8091/api/cryptobot/quotes/BTC?side=SELL&amount=0.01'
```

Response: `ranking[]` with `rank, exchange, ask, bid, spreadPct, receives, receivesUnit,
effectivePrice, fee{network,amount,source}, feeKnown, stale, note`; `unavailable[]` with
`exchange, reason (NOT_LISTED | FETCH_FAILED | DISABLED), detail`; `exchanges[]` (everything
configured, so a missing one is visible). Withdrawal fees: none of the three feeds publishes them,
so `cryptobot.quotes.withdrawal-fees.<exchange>.<asset>.<network>` is a hand-maintained table
reported as `source=CONFIGURED`; an unknown fee is shown as "not applied" (`feeKnown=false`),
never as zero. Metrics: `cryptobot_quotes_fetch_total{exchange,ok}` and
`cryptobot_quotes_fetch_latency_seconds{exchange}`.

Manual validation against the oracle (dev only, needs network): `scripts/validate-quotes-oracle.sh`.

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
- Authorization is a versioned, hashed policy evaluated by a pure function (KAN-436): unknown
  state, a stale snapshot, a strategy that is not enabled or an intent bound to another policy
  version is a DENY, never a default. A proposal approved under one `H_R` does not execute under
  another.
- Tenant = the JWT subject, resolved server-side. No client-supplied tenant header exists.
- No financial operation depends on HTTP alone (KAN-403, Endgame §31): the state machine is
  durable (`version` + status guard in the `UPDATE`), the signature is persisted before
  `sendTransaction`, a broadcast that times out is *uncertain* (left in flight for reconciliation),
  and a never-seen signature past its `lastValidBlockHeight` is `FAILED` **without retry** —
  Solana only deduplicates while the blockhash lives (~90 s), so a retry would be a double trade.
  Ambiguity after `max-attempts` goes to `dead_letter` (`dlq_size > 0` is the alert).
- The LLM never executes: it emits an **intent** with a finite vocabulary (KAN-435, paper §6-7).
  `domain.intent.IntentSchema` refuses anything outside the schema (unknown field, unknown
  action, float amount, missing/forbidden field per action, duplicate JSON key); the accepted
  intent is canonicalized (RFC 8785 + NFC, `JsonCanonicalizer`) and `H_I = SHA-256(C)` is its
  identity end to end (`IntentHash`, rendered `sha256:<hex>`). Two semantically equal intents
  hash equal; the first 128 bits of the hash are the `operationId` of KAN-403, so
  `Idempotency-Key: sha256:…` on `/execute` makes re-submitting the same intent idempotent by
  construction. Fixed vectors, verified against `sha256sum`: `src/test/resources/intent/vectors-v1.json`.

  ```text
  always        : schema_version="1", agent_id, action, strategy_id, policy_version, valid_until_slot, nonce
  BUY SELL SWAP : + input_asset, output_asset, input_amount (u64 as string, minimal units), max_slippage_bps
  REBALANCE     : + target_weights_bps {asset: bps, sum 10000}, counter_asset, max_slippage_bps
  CANCEL        : + target_intent_hash
  HOLD          : nothing else
  ```

### Deterministic policy layer (KAN-436, paper §8 / §11 / §17 / §18)

A prompt that says *"never trade more than $10,000"* is guidance. `trade_value_cents <=
max_trade_value_cents` is authority. The authority lives in `domain/policy/`, pure Java, no
Spring, no clock, no I/O:

| | |
|---|---|
| `PolicyRules` (`R_v`) | `version`, `allowed_assets`, `enabled_strategies`, `max_trade_value_cents`, `daily_limit_cents`, `max_asset_exposure_bps`, `max_slippage_bps`, `max_oracle_age_seconds`, `autonomous_up_to_cents`, `second_agent_up_to_cents`. Sets are sorted and NFC, money in cents, ratios in bps. Refuses non-monotonic tiers at construction: **no valid policy, no service**. `hash()` = `sha256:` of the RFC 8785 text. |
| `PolicyInput` (`I`, `S`) | `IntentFacts` (agent, strategy, policy version, asset, trade value, slippage, valid-until slot) + `StateFacts` (daily exposure, exposure after, oracle age, agent permitted, nonce unused, current slot). Every field boxed: **`null` = unknown = the predicate fails**. The canonical input lists only known facts, so its hash says what was known. |
| `PolicyPredicate` | `POLICY_BOUND` · `ASSET_ALLOWED` · `TRADE_WITHIN_MAX` · `DAILY_LIMIT` · `ASSET_CONCENTRATION` · `SLIPPAGE_WITHIN_MAX` · `ORACLE_FRESH` · `AGENT_PERMITTED` · `STRATEGY_ENABLED` · `NONCE_UNUSED` · `NOT_EXPIRED` — all evaluated, no short-circuit, reported in this order. |
| `PolicyVerdict` | `decision ∈ {ALLOW, DENY, ESCALATE}`, `escalation ∈ {NONE, REQUIRE_SECOND_AGENT, REQUIRE_HUMAN_SIGNATURE}`, `tier`, failed predicates, `policy_hash`, `input_hash`; `hash()` is the verdict's own commitment. |

Tiers (defaults in `cryptobot.policy.authorization`, shared cap `cryptobot.policy.max-trade-usd`):
`≤ $100` ALLOW · `≤ $250` second agent · `≤ $500` human signature · above DENY. Today every
proposal still waits for the human whatever the tier says: ALLOW and REQUIRE_SECOND_AGENT are
**recorded, not acted on** (no autonomous execution, no second validator yet).

Reproducibility is tested three ways: the decision table row by row; golden vectors
(`src/test/resources/policy/vectors-v1.json`) whose canonical strings were written by hand and
whose hashes come from `sha256sum`, not from this code; and a second, independent implementation
of the table compared with the engine over a 5 000-input seeded corpus (`PolicyDeterminismTest`).
Any change to the canonical form is `schema_version` 2 and a new vectors file — v1 is frozen.

### Adversarial benchmark, invariants and chaos (KAN-440, paper §29 / §30 / §36)

`src/test/java/io/lifeengine/cryptobot/benchmark/` is the paper's key experiment as a test:
a seeded generator plays the **fully compromised agent** and emits 10 000 intents — 7 000
inside the policy, 3 000 across the 13 attack classes of §29 (invalid asset, oversized amount,
stale/manipulated oracle, expired intent, reused nonce, invalid signature, wrong policy version,
serialization attack, integer overflow, rounding attack, unauthorized agent, prompt-injected
action) — through `AuthorityLayer`, the execution envelope: `IntentSchema` → Ed25519 over the
canonical bytes → `(I, S)` from the authoritative state → `DeterministicPolicyEngine` → the
independent `ReferencePolicyValidator` must produce the same verdict hash → ALLOW executes,
ESCALATE waits, DENY stops. The envelope is test code composed of production primitives; what
is which is spelled out in its Javadoc (the `TradingIntent → IntentFacts` mapping is the part
still pending in production, see proposal `intent-to-policy-binding`).

Measured (`./mvnw test -Dtest='io.lifeengine.cryptobot.benchmark.*Test'`, report in
`target/benchmark/*.md|json`): **7 000 / 7 000 authorized, 3 000 / 3 000 blocked (BlockRate 1.0),
0 policy violations executed**; same corpus twice ⇒ identical verdict hashes; engine and
reference validator agree on all 6 970 verdicts; 2 000 random byte-level mutations ⇒ 0
executions outside policy. Invariants I1–I7 (`InvariantsTest`: trade limit, replay,
authorization, policy binding, asset restriction, fail-closed, reproducibility) hold over the
corpus plus targeted cases (revoke a live agent, rotate `H_R`, blank every state fact, replay
every executed intent). Chaos (`ChaosTest`, §30): oracle offline · validator offline or
disagreeing · RPC without slot · broadcast uncertain · policy unavailable · duplicate · state
partition · signer unavailable · malformed · old schema ⇒ DENY or PAUSE, never execution.
Latency per stage is in the report (Ed25519 verification dominates, ~320 µs p50 per intent);
inference and on-chain latency are **not** measured here — no LLM and no RPC in the loop.

Two holes the benchmark found in `IntentSchema` and closed in the same PR: `{…}{}` (trailing
tokens) parsed as one document, and a leading/trailing control character (`"paper-v1 "`)
was trimmed away instead of refused. Meters for the funnel: `policy_verdicts_total{decision,
escalation}` and `policy_predicate_failed_total{predicate}`.

### On-chain authority (KAN-437, paper §10–12 / §27 / level 4)

Everything above is verified by the service. `programs/intent-authority/` moves four of those
checks to where the client cannot lie about them: a native Solana program (no Anchor, one
runtime dependency) with three PDAs and three instructions.

| account | seeds | meaning |
|---|---|---|
| policy | `["policy", agent, policy_version u32 LE]` | `H_R` committed by an authority; immutable, revocable; a new version is a new address |
| nonce | `["nonce", agent, nonce u64 LE]` | exists ⇔ consumed — anti-replay is account creation, which the runtime cannot do twice |
| receipt | `["receipt", intent_hash]` | exists ⇔ this exact `H_I` executed (paper §12); carries agent, version, `H_R`, nonce, window and slot |

`Execute{intent_hash, policy_version, policy_hash, valid_until_slot, nonce}` runs the rules as a
pure function in this order — **I1** agent is a transaction signer and the policy account is
*its* registered, non-revoked PDA → **I4** `policy.policy_hash == claimed` → **I2**
`Clock.slot ≤ valid_until_slot` → **I3** receipt and nonce PDAs do not exist — and only then
creates nonce + receipt atomically. The "execution" at this level is the receipt: no transfer, no
swap, devnet only. Each refusal is a stable `Custom(n)` code (`AuthorityError`, 0–12), mirrored in
Java with the invariant it enforces.

Tests: 13 unit (the rules table, sizes) + 14 against a real bank (`solana-program-test`: happy
path, every invariant broken through a signed transaction, revoke by non-authority, immutability,
prefunded-PDA griefing, malformed PDAs) + 2 vectors. `src/test/resources/authority/vectors-v1.json`
is written by the Rust SDK (`find_program_address`, `is_on_curve`, borsh) and asserted by both
sides: the Java `ProgramDerivedAddress` (with dalek's decompression semantics for the curve test)
and `IntentAuthorityProgram` (instruction bytes, account layouts, account lists) reproduce it byte
for byte. What this PR does **not** do: deploy (needs the Solana CLI — a human step,
`programs/intent-authority/scripts/deploy-devnet.sh`) or wire `ExecutionService` to the program
(next issue in the epic).

## Tests

```bash
./mvnw test                     # 334 tests: adapters (recorded responses), engines, state machine, HTTP flow with fake RPC + Runtime, ARS quotes (fixtures, no network), idempotency + crash/reconciliation + outbox (KAN-403), intent schema + canonicalization vectors (KAN-435), deterministic policy: decision table + golden vectors + 2-implementation agreement (KAN-436), adversarial benchmark 10 000 intents + invariants I1–I7 + chaos (KAN-440), PDA derivation + program client vs SDK vectors (KAN-437)
./mvnw -f signer/pom.xml test   # 10 tests: signing policy (every refusal reason), token, signature verification
(cd programs/intent-authority && cargo test)   # 29 tests: on-chain rules, bank simulator, shared vectors (KAN-437)
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
`cryptobot.marketdata`, `cryptobot.quotes`, `cryptobot.risk`, `cryptobot.policy`,
`cryptobot.signer`, `cryptobot.advisor`, `cryptobot.reliability` (outbox publisher and
reconciliation job: intervals, batch sizes, `max-attempts`, `grace`). Nothing secret has a default.
