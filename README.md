# CryptoBot — AI control plane for Solana wallets

> Colosseum · Crypto World's Fair 2026 · Solana track.
> **The agent proposes. You approve. An independent validator re-checks, a limited signer executes —
> on devnet, under policy, after a timelock, with a full audit trail.** Built on Life Engine (Auth · Runtime · observability); this repo holds only
> the crypto domain.

```
wallet → portfolio → risk detection → AI analysis → rebalance proposal
      → economic + on-chain simulation → policy → human approval → timelock
      → independent validator → limited signer → devnet execution → audit trail
```

## What it does today

| Step | What happens | Where |
|---|---|---|
| Track a wallet | Any public Solana address (devnet, or mainnet read-only). SOL + SPL/Token-2022 balances, recent signatures. | `adapters/solana/SolanaRpcClient` |
| Value it | Jupiter Price v3 by mint (keyless), labelled fallback when the oracle is down. Devnet mints are valued as the mainnet asset they represent. | `adapters/marketdata` |
| Detect risk | Deterministic rules: concentration (≥ 60 % HIGH, ≥ 40 % MEDIUM), no stablecoin buffer, dust, unpriced tokens, sharp move since the last snapshot. Since KAN-392 the rules are a **versioned pure-Java engine** (`risk-engine 1.0.0`): integer input in basis points / micro-dollars, integer weights in a hashed JSON (`weightsHash`), discrete output (action, 0–9 buckets, reason codes) — the only L1 step of the pipeline; the prose is rendered afterwards and never enters a hash. | `domain/risk/DeterministicRiskEngine` · `application/controlplane/RiskEngine` (adapter) |
| Ask in natural language | *"¿Cuál es mi mayor riesgo?"* → Life Engine Runtime workflow `crypto.portfolio-advisor.v1` (one LLM stage, strict JSON). The model sees positions, weights and findings — **never a key, never a transaction**. | `AdvisorService` · runtime `ext/cryptomarketreview/portfolio` |
| Propose | *"SOL 70 % → 50 %"* → planner computes the legs; the LLM never sets amounts. | `RebalancePlanner` |
| Simulate | Economic (spot × amount, fee) **and** on-chain: the exact unsigned transaction goes through `simulateTransaction` (`sigVerify=false`, so read-only wallets simulate too). | `SimulationService` |
| Policy | Kill switch · asset allowlist · max USD · max % of portfolio · cooldown · devnet only · lamport cap · vault configured · simulation passed · signer controls the wallet. Each rule is named in the audit trail. | `PolicyEngine` |
| Deterministic authorization (KAN-436) | Versioned policy `R_v` (integers only, `H_R = SHA-256` of its canonical JSON) evaluated as a pure function over `(I, S)`: 11 predicates (`Valid(I) = ∧ Pᵢ`, unknown ⇒ deny) then a tier by trade value → **ALLOW / ESCALATE(second agent \| human signature) / DENY**. The verdict, `H_R`, the input hash and the verdict hash travel with the proposal and the `POLICY_EVALUATED` audit event; execution refuses a proposal decided under another `H_R`. | `domain/policy/DeterministicPolicyEngine` |
| Approve | Explicit human decision, recorded with who/when/note. `execute` before `APPROVED` is a 409. | `ProposalService` |
| Timelock (KAN-438, paper §19) | Approval starts a lock by tier of the verdict: ALLOW ⇒ none, ESCALATE ⇒ 30 min (`cryptobot.policy.timelock`). `execute` inside it is a 409 with the seconds remaining; `POST /proposals/{id}/cancel` withdraws it (`CANCELLED` audit event, `trade.cancelled`). The TTL is pushed so the lock fits. | `ProposalService` · `PolicyEngine` |
| Independent validation (KAN-438, paper §20, level 5) | Before signing, `validator/` — a **separate process** with its **own copy of `R_v` pinned by hash** (`VALIDATOR_POLICY_HASH`; a mismatch refuses to start) — re-derives the verdict over the recorded `(I, S)` with its **own implementation** of the table and returns an Ed25519 **attestation** bound to the proposal, the exact transaction bytes (`message_hash`), `H_R`, the input hash and the verdict hash, valid 90 s. DENY, disagreement, another policy hash, unreachable ⇒ `FAILED` at stage `validate`, nothing signed. At proposal time the validator must be up and on the same `H_R` or the proposal is a paper trade (`VALIDATOR_AVAILABLE`). | `ValidatorClient` · `validator/` |
| Execute (devnet) | Second explicit click. Re-simulates on a fresh blockhash, obtains the attestation, asks the **limited signer** (separate process, own secret, byte-level caps) to sign — the signer refuses without an attestation from the pinned validator key for **these** bytes —, verifies the signature against the wallet key, broadcasts, confirms, links the explorer. | `ExecutionService` · `signer/` |
| Audit | Append-only `audit_event` per transition: created, simulated, policy evaluated, awaiting approval, approved/rejected, started, signed, submitted, executed/failed, reconciled. | `AuditService` |
| On-chain authority (KAN-437, paper level 4) | `programs/intent-authority`: a Solana program (devnet target) that refuses an execution unless the **agent signed** and its policy PDA is registered and not revoked (I1), the claimed `H_R` equals the one **committed on-chain** (I4), the current slot is within `valid_until_slot` (I2), and neither the receipt PDA of `H_I` nor the nonce PDA of `(agent, nonce)` exists (I3) — then leaves a receipt account atomically. Java client (PDAs, instructions, account decoders) byte-exact with the program via shared SDK vectors. Not yet wired into `ExecutionService`, not yet deployed (needs the Solana CLI: human step). | `programs/intent-authority` · `adapters/solana/authority` |
| Decision Receipts (KAN-391, Endgame §6-7) | Every step above leaves a **signed, content-addressed receipt**: `WALLET_SNAPSHOT` → `RISK_DECISION` (L1) · `HUMAN_IDEA` → `MARKET_ANALYSIS` (L0: prompt commitment, model, run id, tokens) → `STRATEGY` (L1) → `RISK_DECISION` (validates) · `SIMULATION` → `EXECUTION`. The id is `SHA-256("life-engine.cryptobot.receipt" ‖ 0x00 ‖ JCS(body))`; the body names its parents, so a cycle cannot be built; Ed25519 by the service key over a domain-tagged hash; `verify` recomputes all of it. Prompts, answers and keys never enter a receipt. | `application/receipt` · `domain/receipt` · `api/controlplane/ReceiptsController` |
| Anchored on devnet (KAN-394, Endgame §11) | Receipts are batched into a **Merkle root** (sorted leaves, domain-separated nodes) and the root goes to Solana devnet in one **SPL Memo** transaction `ir/1 root=<sha256> n=<count> ts=<…>`, signed by the same isolated signer (which re-derives the memo from the bytes and signs it **only on devnet**). A receipt carries `anchor{chain,tx,slot,root,proof}` only once the memo is **finalized** — never at *confirmed*; a dropped or reorged transaction is re-sent for the same root (idempotent), and after `max-attempts` the batch is abandoned and its receipts re-batched. `verify` folds the proof back to the root; `POST /anchors/{root}/verify` recomputes the root from the batch and reads the memo back from the chain. | `application/receipt/AnchorService` · `domain/receipt/MerkleTree` · `api/controlplane/AnchorsController` |
| Provenance DAG + lineage API (KAN-393, Endgame §7 / §15 / §19 step 3) | The receipts form a **content-addressed provenance DAG**. `ancestors` / `descendants` / `parents` / `children` / `reusedBy` per receipt and the lineage of a whole proposal, all bounded `WITH RECURSIVE` walks that never leave the caller's tenant. A `STRATEGY` created without naming an advisor run **reuses** the wallet's latest `MARKET_ANALYSIS` (same asset, < 1 h) with a `REUSES` edge — the edge is in the child's hash. The UI draws the graph per proposal: hash, model/engine, measured compute, level, anchor; click → receipt + live `verify`. | `application/receipt/LineageService` · `application/controlplane/AnalysisReuse` · `infrastructure/…/LineageR2dbcStore` · `api/controlplane/LineageController` · `cryptobot-ui/src/app/lineage` |
| Reliable execution (KAN-403) | `operationId` idempotency key bound **before** signing; optimistic version + status guard on every write; signature persisted **before** broadcast; `EXECUTING`/`SUBMITTED` rows reconciled against `getSignatureStatuses` at startup and every 30 s; transactional outbox (`trade.*` events) with `SKIP LOCKED` worker, backoff and dead-letter queue. | `ExecutionService` · `ReconciliationService` · `OutboxPublisher` |
| Recovery, visible (KAN-571 / KAN-501) | A signature the chain never saw whose blockhash expired is **retried idempotently** — same `operationId`, fresh blockhash, new signature, through validator and signer again (`EXECUTION_RETRIED`) — up to `max-retries`, then dead-lettered `retries_exhausted`; no verdict after `max-attempts` ⇒ dead letter `ambiguous`. The DLQ has a way out: `GET /api/cryptobot/dead-letters` (admin, global, filters), `POST …/{id}/resolve` (settles the proposal to what the chain proves, 409 without a verdict) and `POST …/{id}/requeue` (outbox event `PENDING` again, or reconcile-now with the idempotent retry); `resolved_at/by`, `resolution`, `outcome` on the row, `DEAD_LETTER_RESOLVED/REQUEUED` audit events, `cryptobot_reconciliation_total{outcome}`, `cryptobot_dead_letter_total{reason}`, `cryptobot_dead_letter_open`. The demo injects the failure for real (`scripts/demo/e2e-devnet.sh --chaos rpc-down`, demo-only `CRYPTOBOT_CHAOS_ENABLED`). Runbook: `docs/runbooks/dead-letter.md`. | `ReconciliationService` · `DeadLetterService` · `DeadLettersController` · `application/chaos` |

Legacy (pre-hackathon, still available, not part of the demo): Binance-public watchlist / price
zones / journal / indicators and the 5-agent `crypto.market-review.v1` — now also fed by Solana via
`SolanaSnapshotProvider` (`cryptobot.snapshot.provider=solana-public`, GeckoTerminal pool + Jupiter).

## Mainnet is fail-closed (KAN-493)

Execution and anchoring target **devnet**. Mainnet is not a configuration away: it is refused at
three independent layers, each reading its own explicit flag, and every default is `false`.

| Layer | What it checks | Refusal | Flag |
|---|---|---|---|
| `ExecutionService.execute` | The wallet's cluster **and** the cluster recorded on the proposal, before the proposal moves to `EXECUTING` and before the validator or the signer is asked. Real-engine test: `ExecutionServiceMainnetGateTest`. | `409 MAINNET_DISABLED` | `cryptobot.execution.allow-mainnet` (`CRYPTOBOT_ALLOW_MAINNET`, default `false`) |
| `SolanaRpcClient.sendTransaction` | The cluster passed **with the transaction** — never a global. Refused before any RPC call. | `MainnetDisabledException` (nothing sent; the row is `FAILED`, not "uncertain") | same flag |
| `signer/` (`SigningPolicy` + `AttestationVerifier`) | The `cluster` in the sign request must be the signer's configured cluster and, if it is `mainnet-beta`, the signer needs its own flag; the validator's attestation must carry the **same** `cluster` (it is part of the signed payload since KAN-493). | `403 mainnet_disabled` · `cluster_mismatch` · `cluster_missing` · `attestation_cluster_mismatch` | `signer.allow-mainnet` (`SIGNER_ALLOW_MAINNET`, default `false`) |

`cryptobot.policy.execution-cluster: devnet` (`EXECUTION_CLUSTER` rule) stays as it was — belt and
braces: a mainnet wallet is a paper trade before it is a refused execution.

`SIGNER_REQUIRE_ATTESTATION=false` (the level-5 gate off) is accepted only under the Spring profile
`local` or `test`; under any other profile — or none — the signer **refuses to start** with the
variable named in the message (`AttestationRequirementGuard`).

None of the flags is a go-live switch. Mainnet stays closed until an independent readiness gate
(CB-13) exists.

## Why this and not a crypto chatbot

A chatbot talks. This acts **inside a policy**: every proposal is simulated against the real chain
before a human sees it, the human decision is a persisted record, and the only component that can
sign is a separate process that refuses anything but an allow-listed transfer under a cap. The LLM
cannot skip a rule because it never touches the pipeline after "suggest".

## Run it locally (4 processes + Life Engine dev stack)

Prerequisites: Java 21, Maven, Node 24, Postgres `:5433` (`life_engine_cryptobot`), Life Engine
Auth `:8081` and Runtime `:8090` running (`scripts/dev-up.sh` in the workspace), Ollama with the
Runtime's `chat` role model.

```bash
# 1. validator — a process that is not the agent (KAN-438). Its policy must hash to the service's
#    H_R (the log prints it at boot; pin it with VALIDATOR_POLICY_HASH). Its attestation key moves
#    no funds: generate one like the wallet key (see validator/README.md) and give the signer its pubkey.
export VALIDATOR_KEYPAIR_PATH=~/.cryptobot-demo/validator.json VALIDATOR_TOKEN=<validator token>
export VALIDATOR_POLICY_HASH=<sha256:… printed by the service and the validator>
mvn -f validator/pom.xml spring-boot:run                                # :8097

# 2. signer — the only process with a wallet key (generate one: see signer/README.md)
export SIGNER_KEYPAIR_PATH=~/.cryptobot-demo/demo-wallet.json SIGNER_TOKEN=<service token>
export SIGNER_ALLOWED_DESTINATIONS=<rebalance vault pubkey>
export SIGNER_VALIDATOR_PUBLIC_KEY=<validator pubkey>                   # without it nothing is ever signed
mvn -f signer/pom.xml spring-boot:run                                   # :8096

# 3. service — JWT_SECRET (≥32 bytes) or AUTH_JWKS_URI is REQUIRED: since KAN-350 no profile
#    ships a default secret; without either, the service refuses to start.
export JWT_SECRET=<same as auth/runtime> AUTH_JWKS_URI=http://127.0.0.1:8081/.well-known/jwks.json
export CRYPTOBOT_REBALANCE_VAULT=<rebalance vault pubkey>
export CRYPTOBOT_SIGNER_ENABLED=true CRYPTOBOT_SIGNER_TOKEN=<service token>
export CRYPTOBOT_VALIDATOR_ENABLED=true CRYPTOBOT_VALIDATOR_TOKEN=<validator token>
export CRYPTOBOT_ADVISOR_LOCALE=es
./mvnw spring-boot:run                                                  # :8091

# 4. UI
cd ../cryptobot-ui && npm ci && npx ng serve --port 4204               # http://localhost:4204
```

Or, with Docker (validator + signer + service + UI; Auth/Runtime stay on the host):

```bash
cp .env.hackathon.example .env.hackathon   # fill the secrets/addresses
docker compose -f docker-compose.hackathon.yml --env-file .env.hackathon up -d --build
open http://localhost:4204
```

Devnet SOL for the demo wallet: https://faucet.solana.com (the RPC airdrop is rate-limited).

### The demo, one command, from zero (KAN-575 / HK-7)

```bash
scripts/demo/run.sh            # → out/demo-report-<ts>.md, < 10 min after the first image build
```

On a machine with Docker, curl, python3 and git: generates the keys and `.env.demo` if missing,
picks devnet when the demo wallet holds SOL there (else the local `solana-test-validator`, and says
so), brings up `docker-compose.demo.yml` — own Postgres, independent validator, isolated signer, no
Auth/Runtime — and runs the story in four acts, each step printed with its evidence: **execute**
(intent → policy → approval → timelock → validator → signer → Solana → confirmed → `EXECUTED` →
receipt → replay → mainnet 409), **risk** (an adversarial intent → `BLOCKED_BY_POLICY` with the
rules that failed; the cooldown), **recovery** (RPC down at broadcast → dead letter → requeue →
idempotent retry → one transaction on chain) and **evidence** (receipt DAG, Merkle anchor of the
receipts finalized on Solana, inclusion proof, metrics). The report and the log are grepped for
every secret before the script exits. `--target uat` runs the same acts against a deployed
service (`.env.demo-uat.example`). Details and the underlying scripts (`wallet-devnet.sh`,
`e2e-devnet.sh --chaos …`, `--it` for `E2EDevnetIT`): `scripts/demo/README.md`.

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
| `GET /api/cryptobot/receipts/{hash}` · `POST …/verify` · `GET /api/cryptobot/proposals/{id}/receipts` · `GET /api/cryptobot/wallets/{id}/receipts` · `GET /api/cryptobot/receipts/signing-key` | a receipt with its edges · recompute hash + body + signature + parents and, for an L1 `RISK_DECISION`, **re-run the engine** on the stored input and compare `outputHash` (`reproduced`, `reproduction.reason`, KAN-392) · the receipts of a proposal (oldest first) / a wallet (newest first) · the public key and the three formulas to verify offline (KAN-391) |
| `GET /api/cryptobot/receipts/{hash}/lineage?direction=ancestors\|descendants\|both&depth=N` · `…/ancestors` · `…/descendants` · `…/parents` · `…/children` · `…/reused-by` · `GET /api/cryptobot/proposals/{id}/lineage?direction=…&depth=N` | the DAG around a receipt (nodes with hash, kind, agent, model/engine, compute, cost, level, anchor + explorer link, degrees; typed edges; `lineageRoots`; `truncated`; summary) · direct neighbours with the edge role · children that declared `REUSES` · the DAG of a proposal: its receipts at depth 0 and everything they came from (KAN-393). `depth` defaults to 16, cap 64; other owner ⇒ 404; bad direction ⇒ 400 `INVALID_DIRECTION` |
| `POST /api/cryptobot/anchors[?wait=true]` (admin) · `GET /api/cryptobot/anchors` · `GET …/anchors/{root}` · `POST …/anchors/{root}/verify` | settle in-flight batches, retry failed ones, open one for the receipts waiting (`wait` polls for finality) · recent batches with their explorer link · one batch with the caller's own receipts in it · recompute root + fold every proof + parse the memo + read the transaction back from devnet (KAN-394). `POST …/receipts/{hash}/verify` now also returns `anchor{anchored,status,tx,slot,root,proof,proofValid,explorerUrl}` |
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
- Three independent emergency stops: `CRYPTOBOT_EXECUTION_ENABLED=false` (service),
  `VALIDATOR_ENABLED=false` (validator: every verdict becomes DENY) and `SIGNER_ENABLED=false`
  (signer). Any one of them alone is enough.
- Privilege separation (KAN-438, paper §20 / §21): the agent process never holds the wallet key
  and cannot make the signer use it on its own — the signer needs an attestation from the
  validator, whose key moves no funds and which holds its own hash-pinned copy of the policy. A
  compromised service can propose, lie about the facts it recorded, and ask; it cannot sign, it
  cannot change the policy the validator checks against, and it cannot reuse an attestation for
  other bytes or after 90 s. What the validator does **not** verify yet: that the recorded facts
  are true (post-MVP: it reads state from the chain itself) and that the human approval happened
  (post-MVP: the human signs the intent hash). Multisig / HSM are post-MVP too.
- Execution is devnet-only by configuration (`cryptobot.policy.execution-cluster`), not by
  convention; mainnet wallets are read-only paper trades.
- Authorization is a versioned, hashed policy evaluated by a pure function (KAN-436): unknown
  state, a stale snapshot, a strategy that is not enabled or an intent bound to another policy
  version is a DENY, never a default. A proposal approved under one `H_R` does not execute under
  another.
- Tenant = the JWT subject, resolved server-side. No client-supplied tenant header exists.
- Calls to Runtime always carry `Authorization: Bearer` (KAN-69). Requests made by a user forward
  that user's JWT verbatim (`cryptobot.runtime.auth-mode=passthrough`, the default — Runtime sees
  the real identity and tenant). Headless callers (the monitoring loop) and every call when
  `auth-mode=service` use the service's own credential: an RS256 token obtained from Auth's
  client-credentials endpoint (`POST {AUTH_INTERNAL_BASE_URL}/api/auth/internal/service-token`,
  `aud=runtime`, `sub=service:cryptobot`), cached per audience and renewed before expiry. There is
  no fallback to a static token or to an unauthenticated request; a real environment refuses to
  start when the S2S path is active and the credential is missing.
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

### Independent authority (KAN-438, paper §17 / §19 / §20 / §21 / level 5)

```
AI process (cryptobot-service)     Independent Validator (validator/)      Limited Signer (signer/)
  records (I, S), H_R, verdict  →   own R_v pinned by VALIDATOR_POLICY_HASH   own wallet key, byte-level caps
  builds the unsigned tx            own table re-derives the verdict          requires attestation by the
  asks for an attestation           binds it to sha256(message) + 90 s TTL   pinned validator key, for these
  asks the signer WITH it       →   signs with a key that moves nothing   →   bytes, not DENY, not expired
```

- **Fail-closed (§17)**, on every side: a fact the service could not resolve is `null` and fails
  its predicate; the validator denies on `POLICY_HASH_MISMATCH`, `VERDICT_DISAGREEMENT` or
  `VALIDATOR_DISABLED`; a validator that cannot load its policy or whose policy does not hash to
  the pin **does not start**; a signer without a pinned validator key signs nothing; the service
  fails the execution at stage `validate` (metric `trade.failed{stage=validate}`,
  `validator.attestations{result=refused}`) before the signer is asked.
- **Timelock (§19)**: `cryptobot.policy.timelock.escalated` (30 min) for ESCALATE verdicts,
  `autonomous` (0) for ALLOW; `executableAt` is persisted in the approval record and re-checked at
  execution; `POST /proposals/{id}/cancel` while it runs.
- **Independence (§20)**: `validator/` shares no jar with the service (copy-not-reuse, like the
  signer) and decides with `IndependentPolicyTable`, a second implementation of the §8 table that
  must reproduce the same golden vectors (`policy/vectors-v1.json`) hash for hash. The service
  compares the validator's `H_R` and verdict hash with what it recorded and refuses on any
  difference — two independent readings of the schema must agree before a byte is signed.
- **Keys (§21)**: the wallet key never leaves the signer; the validator's key is a capability to
  attest, not to spend; both are pinned by public key, both live at a read-only mount, neither is
  in a log. The attestation carries `proposal_id`, `message_hash`, `policy_hash`, `input_hash`,
  `verdict_hash`, `decision`, `validator`, `issued_at`, `expires_at` (canonical JSON, Ed25519).
- Audit: `EXECUTION_VALIDATED` (validator, decision, hashes, expiry, signature) precedes
  `EXECUTION_SIGNED`; the signer logs `validator` and `verdictHash` on every signature.

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

### Decision Receipts (KAN-391, Endgame §6–7 / §10)

Audit events say *what happened*; receipts make it **verifiable by someone who does not trust
this database**. One receipt per step, schema `ir/1`:

| | |
|---|---|
| id | `receiptHash = SHA-256("life-engine.cryptobot.receipt" ‖ 0x00 ‖ canonical)`, `canonical` = RFC 8785 (`JsonCanonicalizer`: sorted keys, no whitespace, NFC, integers or decimal strings only, absent ≠ null). The body lists `parents[]` (sorted), so the child's id depends on its parents' ids: **no cycles, no forward references**, by construction — the store adds the FK. |
| body | `kind`, `tenantId`/`ownerId` (the JWT subject, server-side), `agentId`, `parents[]`, `inputs[{type,hash}]`, `promptHash`, `model{ref,provider,providerDigest,weightsHash}`, `engine{id,version,weightsHash}`, `runtime{runId,serviceVersion,serviceCommit}`, `params`, `output{hash,schema,artifactRef}`, `compute{inputTokens,outputTokens,units,wallMs}`, `reproducibility`, `startedAt`, `completedAt`, `nonce`, `refs{walletId,proposalId,snapshotId}`. Hashes, ids, versions, counts, timestamps — **never a prompt, an answer, a chunk or a key**. |
| privacy | the user's question and the payload sent to the Runtime are committed as `H(salt_tenant ‖ 0x00 ‖ text)`, `salt_tenant = HMAC-SHA256(secret, tenantId)`: a short guessable text cannot be confirmed by brute force from the receipt; the salt opens it in an audit. |
| signature | Ed25519 by the service key (`cryptobot.receipts.signing-key`, 64-byte `seed ‖ pub`, from `secrets/`; `key-id` for rotation) over `"life-engine.cryptobot.receipt.sig" ‖ 0x00 ‖ receiptHashBytes`. No key configured ⇒ ephemeral key, WARN in the log, fine for a dev box. `GET /receipts/signing-key` publishes the public half. |
| levels | `L0_SIGNED` for everything the LLM or the chain touched; `L1_REPRODUCIBLE` only for the pure-Java engines (`risk-engine`, `rebalance-planner`), whose `output.hash` another node recomputes from the same inputs. `verify` re-executes `risk-engine` receipts (KAN-392, below) and reports `reproduced` = `true` / `false` / `null` (not L1, or an engine this build cannot re-run — today the planner); a `false` makes the receipt invalid. A receipt is never marked reproduced because it says so. |
| replay | `UNIQUE (tenant_id, nonce)`: snapshot id, message id, Runtime run id, proposal id, `sim:<proposal>`, `exec:<operationId>` — the idempotency key of KAN-403 is also the receipt's replay guard; the reconciler leaves the same `EXECUTION` receipt the interrupted path would have. |
| tables | `intelligence_receipt` (body JSONB + the exact canonical bytes + signature + anchor columns, filled later by the memo batch), `receipt_edge` (child → parent, role `DERIVES_FROM \| VALIDATES \| EXECUTES \| REUSES`), `artifact` (content-addressed outputs, `storage_ref` into the aggregate). Migration `V6`. The lineage API walks `receipt_edge` with a bounded `WITH RECURSIVE` (KAN-393, below). |
| what is measured | tokens and latency come from the Runtime run's `LLM_CALL_SUCCEEDED` events; no tokens ⇒ no `compute` block. `cost` is never claimed (no price table yet). Meters: `intelligence_receipts_total{result=issued\|verified\|invalid\|failed}`, `deterministic_inference_total`. |

Golden vectors (`src/test/resources/receipt/vectors-v1.json`) were produced outside this code
(python `json` + `hashlib` + `cryptography`): canonical strings, hashes, deterministic Ed25519
signatures and salted commitments (NFC and NFD forms of the same question commit equal). v1 is
frozen; a change of canonicalization is `ir/2` and a new file.

### Deterministic risk engine (KAN-392, Endgame §5 / §10)

The one thing in the pipeline that is **L1 for real**. Not "deterministic AI": the LLM proposes
(L0), this validates, classifies and bounds — *the final decision passes through a deterministic,
versioned, reproducible validator*.

| | |
|---|---|
| model | `DeterministicDecision(engineId, engineVersion, weightsHash, canonicalInput) → canonicalOutput`. `risk-engine 1.0.0`: the six rules above plus scoring, as a pure function over `int`/`long` — no floating point, no clock, no randomness, no threads, no native code. Java specifies that arithmetic exactly, so the output bytes are the same on x86_64, ARM64 and the CI runner by construction. |
| input | `risk-input/1` (`RiskInput`): positions sorted by mint with `exposureBps` (0–10 000), `valueUsdMicros`, `held`/`priced`/`stable`; `totalUsdMicros`; optional `deltaBps` + `largestMoveAsset`. The quantisation from the valued snapshot (`HALF_UP`) is part of the model. Its hash is declared in the receipt as input type `RISK_INPUT`. |
| weights | `src/main/resources/risk-engine/weights-v1.json`, integers only (thresholds in bps / micro-dollars, severity points, bucket rules). `weightsHash = SHA-256(JCS(file))` — what every `RISK_DECISION` receipt names under `engine.weightsHash`. No runtime override on purpose: a property-driven threshold would make the receipt describe a file the engine was not running. An edited value is a new model ⇒ bump the version. |
| output | `risk-decision/1` (`RiskVerdict`): `action ∈ {HOLD, BUY, SELL, AVOID}` (+ `asset` for `SELL`), `riskBucket`, `confidenceBucket`, `maxPositionBucket` (0–9), `reasons[]` (codes), `signals[]` (rule, severity, integer metric/threshold), `overall`, `score`. Ties broken by the input's fixed order. `output.hash` = SHA-256 of its canonical bytes. |
| re-execution | `deterministic_inference` (migration `V7`) keeps the input and output trees next to each L1 receipt, written in the same transaction; `POST /receipts/{hash}/verify` recomputes both hashes from the trees, checks they are the ones the receipt names, runs the engine again and compares. Reasons: `REPRODUCED`, `OUTPUT_HASH_MISMATCH`, `INPUT_HASH_MISMATCH`, `STORED_OUTPUT_HASH_MISMATCH`, `INFERENCE_MISSING`, `WEIGHTS_UNAVAILABLE` (an older weights file this build does not ship), `ENGINE_UNKNOWN` (L1 claimed by an engine without a re-executor), `NOT_L1`. A mismatch increments `deterministic_mismatch_total` and logs `reproducibility_mismatch`. |
| golden | `src/test/resources/risk-engine/golden-v1.json`: **200 inputs → 200 `inputHash` / `outputHash`**, 21 hand-picked edge cases (empty, exact thresholds, ties, dust, unpriced, the full penalty stack) + 179 generated from a fixed seed, inlined so the file is the evidence. `RiskEngineGoldenTest` re-runs all of them and also pins `weightsHash` and the version: any drift without a version bump is red, locally and in CI. The 200 input hashes and the weights hash were cross-checked with an independent Python JCS + `hashlib` implementation. Regenerate only after a bump: `./mvnw test -Dtest=RiskEngineGoldenTest -Drisk.golden.write=$PWD/src/test/resources/risk-engine/golden-v1.json`. |

### Anchoring on Solana devnet (KAN-394, Endgame §11)

A signed receipt proves *Life Engine said so*; the anchor proves *when*, to anyone who does not
trust this database. No program of our own (that is phase 2): one SPL Memo per batch.

| | |
|---|---|
| tree | `MerkleTree`: leaves = the batch's receipt hashes, de-duplicated and **sorted** (the root is a function of the set, so anyone can recompute it from the members with no stored order); `leaf = SHA-256(0x00 ‖ hash)`, `node = SHA-256(0x01 ‖ left ‖ right)`, odd node promoted. Proof = siblings leaf-up, `L:sha256:…` / `R:sha256:…`. Vectors from python `hashlib`: `src/test/resources/receipt/merkle-vectors-v1.json`. |
| memo | `ir/1 root=<sha256:…> n=<count> ts=<ISO-8601 Z>` — a root, a count, a time. Nothing else ever goes on-chain. |
| signer | `POST /api/signer/sign-anchor {root, receiptCount, unsignedTransactionBase64, expectedFeePayer}`: the signer decodes the bytes, requires exactly one Memo instruction with **no accounts** whose text is exactly the memo for that root and count, and refuses on any cluster but devnet (`anchor_cluster_not_devnet`). The transfer policy is untouched: a memo on `/sign` is still `program_not_allowed`. |
| states | `PENDING → SUBMITTED → FINALIZED` (receipts stamped here) · `SUBMITTED → FAILED` on an on-chain error or when never seen past `lastValidBlockHeight` · `FAILED → SUBMITTED` again for the **same root and memo** (idempotent re-anchor) · `ABANDONED` after `max-attempts` (its receipts go to a new batch; the same set reopens the same root). The SUBMITTED row, with the transaction id, is written **before** `sendTransaction`. |
| tables | `receipt_anchor` (root PK, status, memo, tx, slot, blockhash, attempts…) and `receipt_anchor_member` (root, receipt_hash, proof JSONB), migration `V8` (`V7` is KAN-392's). The anchor columns of `intelligence_receipt` are the only thing the anchoring path ever writes on a receipt. |
| job | off by default (`cryptobot.anchor.enabled`, needs the signer); `POST /api/cryptobot/anchors` (`RUNTIME_ADMIN`) runs the same sweep on demand, `?wait=true` polls for finality up to `finality-wait`. `cryptobot.anchor.cluster` accepts only `devnet`: anything else refuses to start. |
| meters | `receipt_anchors_total{result=submitted\|finalized\|failed\|abandoned}`, `anchored_receipts_total`, `anchor_pending` (gauge: receipts without a finalized anchor), `anchor_finality_latency_seconds`. |


### Provenance DAG and lineage (KAN-393, Endgame §7 / §15)

The receipts are already a DAG (a child's hash commits to its parents' hashes; `receipt_edge`
mirrors it with an index). KAN-393 makes it queryable and visible:

| | |
|---|---|
| walks | `LineageRepository.walk(roots, tenant, direction, depth)` — one `WITH RECURSIVE` per direction in Postgres (`LineageR2dbcStore`), anchored on the roots **filtered by tenant** and joining every reached receipt on the same tenant: a walk cannot cross a tenant even if an edge did. `UNION` + `depth < :maxDepth` bound the work; the DAG has no cycles by construction, so the cap (`64`, default `16`) is a cap, not a safety net. A receipt reachable by several paths comes back once, at its minimum depth. |
| graph | `LineageService.Graph`: `roots` (depth 0), `nodes` (hash, kind, agent, level, depth, model / engine, measured compute, cost when priced, anchor + explorer link, run id, output hash/schema, key id, `parentCount`/`childCount`, refs), `edges` of the induced subgraph with their role, `lineageRoots` (nodes with no parent anywhere: the origin — a wallet snapshot), `truncated` (a node on the last layer still has edges beyond the graph), `summary` (units, tokens, cost only when every priced node shares one price table, anchored, `reused`, by level, by kind). Nothing private: the body never had it. |
| `REUSES` | `AnalysisReuse`: a `STRATEGY` created **without** `runtimeRunId` looks up the wallet's latest `MARKET_ANALYSIS`; if it completed less than `cryptobot.receipts.reuse-window` ago (default `1h`, `PT0S` disables) **and** its assistant turn's `suggestedActions` name an asset the intent touches, the analysis becomes a parent with role `REUSES` — inside the strategy's hash. Both facts are read from the store; an analysis whose assets cannot be read is not reused. The `PROPOSAL_CREATED` audit event carries `analysisReceipt` + `analysisRole` (`DERIVES_FROM` when the run was named, `REUSES` when it was reused). Meter: `artifact_reuse_total{external=false}` (`external=true` waits for public receipts, P1). |
| tenancy | every endpoint resolves the owner from the JWT and the tenant from the owner (`tenantId = ownerId`, V4); a receipt or proposal of another owner is a 404. "Public" receipts (§7 *publicado*) do not exist yet: no column, no visibility — when they do, `LineageR2dbcStore` is the only place that admits a public parent. |
| UI | `cryptobot-ui/src/app/lineage`: layered SVG of the proposal's DAG (parents above children, longest-path layering, one barycenter pass), node = kind · level · short hash · producer · ⚓ when anchored; dashed teal edge = `REUSES`, purple = `VALIDATES`, orange = `EXECUTES`. Click → the stored receipt (parents with roles, inputs, prompt commitment) and a live `POST …/verify`, one line per check. Meter: `provenance_depth` (distribution of the max depth returned). |

## Tests

```bash
./mvnw test                     # 458 tests (1 skipped: the golden writer): independent validator client + fail-closed execution + timelock/cancel + default-policy parity (KAN-438), adapters (recorded responses), engines, state machine, HTTP flow with fake RPC + Runtime, ARS quotes (fixtures, no network), idempotency + crash/reconciliation + outbox (KAN-403), intent schema + canonicalization vectors (KAN-435), deterministic policy: decision table + golden vectors + 2-implementation agreement (KAN-436), adversarial benchmark 10 000 intents + invariants I1–I7 + chaos (KAN-440), PDA derivation + program client vs SDK vectors (KAN-437), receipt vectors + DAG invariants + verify + the 7-kind DAG over the HTTP flow (KAN-391), risk engine: canonical input/output, action table, tie-breaks, 200-hash golden, L1 re-execution with every reason code (KAN-392), Merkle vectors + memo format + anchoring batch (submit / finalized / re-anchor / abandon / verify) + the anchor flow over HTTP with a signing fake (KAN-394), lineage walks (ancestors/descendants/both, depth cap, tenant boundary, reuse) + REUSES over the HTTP flow (KAN-393)
CRYPTOBOT_IT_PG_HOST=127.0.0.1 ./mvnw test -Dtest=ReceiptR2dbcStoreIT,LineageR2dbcStoreIT   # opt-in, real Postgres (dev box :5433, own schema kan391_it): V6/V7 + the WITH RECURSIVE walks
./mvnw -f signer/pom.xml test   # 21 tests: signing policy (every refusal reason, transfer and anchor memo), token, signature verification, attestation gate (KAN-438)
./mvnw -f validator/pom.xml test  # 25 tests: independent table vs golden vectors, agreement/disagreement/hash pin, attestation, HTTP (KAN-438)
(cd programs/intent-authority && cargo test)   # 29 tests: on-chain rules, bank simulator, shared vectors (KAN-437)
./mvnw -Pe2e-devnet verify      # E2EDevnetIT (2): the real pipeline against the demo stack + devnet (KAN-570); needs scripts/demo/ up
cd ../cryptobot-ui && npx ng test   # 24 tests: api helpers, glossary, lineage layout + formatting (KAN-393)
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
`cryptobot.marketdata`, `cryptobot.quotes`, `cryptobot.policy`,
`cryptobot.policy.timelock`, `cryptobot.signer`, `cryptobot.validator`, `cryptobot.advisor`, `cryptobot.reliability` (outbox publisher and
reconciliation job: intervals, batch sizes, `max-attempts`, `grace`), `cryptobot.receipts`
(`key-id`, `signing-key`, `salt-secret` — see `.env.template`; `reuse-window`, default `1h`, KAN-393), `cryptobot.anchor` (`enabled`,
`cluster` = devnet only, `interval`, `batch-size`, `max-attempts`, `finality-wait`). Nothing
secret has a default (`DevSecretNotShippedTest` fails the build if one appears in `application.yml`).

Service-to-service credential towards Runtime (KAN-69):

| Env var | Default | Notes |
|---|---|---|
| `CRYPTOBOT_RUNTIME_AUTH_MODE` | `passthrough` | `service` = every Runtime call with the S2S token; needs client `cryptobot` in Auth and `service:cryptobot` in Runtime's allowlist |
| `AUTH_INTERNAL_BASE_URL` | `""` | internal Auth URL (never the public one); required when the S2S path is active in a real environment |
| `CRYPTOBOT_S2S_CLIENT_ID` | `""` | `cryptobot`; required as above |
| `CRYPTOBOT_S2S_CLIENT_SECRET` | `""` | the client's secret in Auth; SOPS/compose, never the repo |
| `CRYPTOBOT_S2S_REFRESH_MARGIN_SECONDS` / `CRYPTOBOT_S2S_TIMEOUT_SECONDS` | `30` / `5` | |
| `CRYPTOBOT_DB_USER` / `CRYPTOBOT_DB_PASSWORD` | `""` | required outside profile `local` (which keeps the dev pair) |

"S2S path active" = `CRYPTOBOT_RUNTIME_AUTH_MODE=service` or `CRYPTOBOT_MONITORING_ENABLED=true`
(the scheduled loop has no user behind it). "Real environment" = profile `prod`/`uat` or `APP_ENV`
other than `local`/`test`/`dev`.
The risk thresholds are **not** configuration since KAN-392: they are the versioned weights file
(`risk-engine/weights-v1.json`) whose hash every receipt names.
