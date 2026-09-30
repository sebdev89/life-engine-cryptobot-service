# Proof of Value V1 (KAN-818), V2–V4 + V6 (KAN-819) and V5 immediate reward (KAN-822)

Life Engine records an **accepted** contribution as a `ValueEvent`, anchors it on Solana devnet and
correlates the on-chain hash with the off-chain evidence. No new service, no signer change: a
ValueEvent is one more Decision Receipt (`ReceiptKind.VALUE_EVENT`, Flyway `V12__pov_value_event.sql`).

## Model

```
request ─▶ AcceptancePolicy V1 ─▶ DistributionPolicy pov/equal-split/v1 ─▶ canonical event (RFC 8785)
        ─▶ valueEventHash = sha256(canonical) ─▶ VALUE_EVENT receipt (output.hash = valueEventHash, signed)
        ─▶ pov_value_event + pov_contribution ─▶ anchoring sweep: Merkle root ─▶ memo "ir/1 root=… n=… ts=…" on devnet
```

| Piece | Rule |
|---|---|
| `pov_identity` | `(tenant_id, id)`, kind `HUMAN`/`AGENT`, `ownerId`/`operatorId` name identities of the same tenant (FK). Idempotent by id. |
| AcceptancePolicy `pov/acceptance/v1` | the five stages `MERGED, BUILT, DEPLOYED, RUNNING, ACCEPTED` present and `true`; otherwise **422** `ACCEPTANCE_POLICY_REJECTED`, one detail per stage. |
| DistributionPolicy `pov/equal-split/v1` | 100 units in equal parts, remainder to the first contribution (request order). 3 contributions → 34/33/33. No other policy is accepted (400). |
| `artifactHash` | `sha256(JCS(artifact))`, artifact = `{commitSha, prUrl?, imageDigest?}` (absent fields omitted). |
| `acceptanceHash` | `sha256(JCS(acceptance))`, acceptance = `{source, environment, stages{5}, evidenceRef?, acceptedAt}`; unknown stage keys are not part of it. |
| `valueEventHash` | `sha256(canonical)`; the canonical event carries tenant, project, task, title, artifact + hash, acceptance + hash, contributions with units, knowledge assets, compute receipts, policies. Stored verbatim in `pov_value_event.canonical`. |
| Receipt | kind `VALUE_EVENT`, `output.hash = valueEventHash`, inputs `CONTRIBUTION_EVIDENCE = artifactHash` and `ACCEPTANCE_EVIDENCE = acceptanceHash`, flat params (projectId, taskId, title, policies, totals). `ir/1` params are flat by schema, so the full event is committed through `output.hash`, not nested in the body. |
| Tenancy | the JWT subject (`Receipts.tenantOf`), never a request field. Another tenant's identity/event is a 404. |
| Idempotency | the same content again (same `valueEventHash` in the tenant) returns the stored event with **200**; a new one is **201**. |

## Endpoints (base `/api/cryptobot`, JWT)

Roles: all under the catch-all `RUNTIME_OPERATOR` of `CryptobotSecurityConfig`, like `/receipts`.
`POST /value-events?anchor=true` also needs `RUNTIME_ADMIN` (403 otherwise): it runs the same sweep as
`POST /anchors?wait=true`, which is admin-only because it sends a devnet transaction.

| Method | Path | |
|---|---|---|
| POST | `/identities` | `{id, kind, displayName, wallet?, ownerId?, operatorId?}` → 201 / 200 |
| GET | `/identities`, `/identities/{id}` | |
| POST | `/value-events[?anchor=true]` | body below → 201 / 200, 400 validation, 422 acceptance |
| GET | `/value-events?limit=N` | newest first (default 20, max 100) |
| GET | `/value-events/{id}` | same shape; `status` RECORDED → ANCHORED as soon as a sweep finalizes the batch |
| GET | `/value-events/{id}/proof` | the `/receipts/{hash}/verify` checks (unwrapped) + `anchor` (Merkle inclusion) + `receiptHash`, `root`, `txSignature`, `slot`, `explorerUrl`, `valueEventHashValid`, `verified` |

```json
{"projectId":"cryptobot","taskId":"KAN-818","title":"Improve CryptoBot opportunity detection",
 "artifact":{"commitSha":"<sha>","prUrl":"https://github.com/…/pull/N","imageDigest":"sha256:…"},
 "acceptance":{"source":"release-truth","environment":"uat-k8s",
   "stages":{"MERGED":true,"BUILT":true,"DEPLOYED":true,"RUNNING":true,"ACCEPTED":true},
   "evidenceRef":"release-truth uat cryptobot --json","acceptedAt":"2026-09-30T10:00:00Z"},
 "contributions":[{"identityId":"sebas","role":"SPECIFIER"},{"identityId":"dev-agent-17","role":"IMPLEMENTER"}],
 "knowledgeAssets":[],"computeReceipts":[],"distributionPolicy":"pov/equal-split/v1"}
```

Response: `{id, receiptHash, valueEventHash, artifactHash, acceptanceHash, status, anchorStatus, anchor:{root, txSignature, slot, explorerUrl}|null,
distributionPolicy, totalUnits, contributions:[{identityId, displayName, kind, role, units}], artifact:{…}, acceptance:{…},
knowledgeAssets, computeReceipts, projectId, taskId, title, acceptedAt, createdAt}`. Roles: `SPECIFIER, ARCHITECT, IMPLEMENTER, REVIEWER,
KNOWLEDGE_PROVIDER, COMPUTE_PROVIDER, OPERATOR, CAPITAL_PROVIDER`. `explorerUrl` is the devnet explorer link; with a local validator
(RPC on localhost or a compose service name) it is the explorer's custom-cluster link to that RPC's origin.

Metrics: `pov_value_events_total{status=recorded|anchored|rejected}`, `pov_identities_total`.

## V2–V4 + V6 — attribution primitives (KAN-819: KAN-820, KAN-821, KAN-823)

Flyway `V13__pov_attribution.sql`. Nothing new goes on-chain: knowledge assets and compute receipts are part of the
event's canonical JSON, so the `VALUE_EVENT` receipt's `output.hash` — and the Merkle root in the `ir/1` memo — cover
them. An event that carries either is schema `pov/value-event/v2`; one without either stays `pov/value-event/v1` byte
for byte (V1 hashes and idempotency do not move).

| Piece | Rule |
|---|---|
| V2 AgentIdentity | an `AGENT` needs a `wallet` on creation (**422** `AGENT_WALLET_REQUIRED`); a `HUMAN` may have none. A wallet is a Solana public key: Base58 that decodes to exactly 32 bytes (**422** `INVALID_WALLET`). Only the public key is registered; keypairs never reach the service. A V1 agent stored without a wallet stays readable and gets its wallet **once** when posted again with one; a stored wallet is never overwritten. |
| Reputation V2 | explicit counts, no formula: `acceptedOutcomes` = distinct accepted events the identity contributed to, `totalUnits` = units it received, `firstAcceptedAt` / `lastAcceptedAt`. |
| V3 KnowledgeAsset | `pov_knowledge_asset(id, version, kind, title, creator_id, content_hash, parent_ids[])`. `kind` ∈ `ARCHITECTURE, PROMPT, RULESET, DATASET, EVAL_SUITE, STRATEGY, RUNBOOK, ALGORITHM, AGENT_CONFIG, DOMAIN_KNOWLEDGE`. Idempotent by id; creator and parents must exist (**422** `UNKNOWN_IDENTITY` / `UNKNOWN_KNOWLEDGE_ASSET`). Only the `sha256` of the content is stored. |
| Knowledge in an event | `knowledgeAssets: [assetId]`, each registered (**422** `UNKNOWN_KNOWLEDGE_ASSET`, one detail per id), no repeats (400). Committed expanded: `{id, version, kind, title, creatorId, contentHash}`. |
| Provenance contribution | if an asset's creator is not among the request's `KNOWLEDGE_PROVIDER` contributions, the service appends one `KNOWLEDGE_PROVIDER` contribution per such creator (in asset order), **before** the split, with `derivedFrom: [assetIds]`. It is provenance — the asset was used, its creator is credited like any other contributor — not an economic decision: the policy is still `pov/equal-split/v1`, visible and recomputable from the canonical JSON. A creator already credited as `KNOWLEDGE_PROVIDER` is not added twice. |
| V4 ComputeReceipt | `computeReceipts: [{providerId, node, model, inputTokens, outputTokens, gpuSeconds, estimatedCostMicroUsd}]`. The provider must be a registered identity (**422** `UNKNOWN_COMPUTE_PROVIDER`) with a wallet (**422** `PROVIDER_WALLET_REQUIRED`), which is denormalized as `providerWallet`. `gpuSeconds` has at most 3 decimals and is committed as integer `gpuMillis` (the canonical JSON is integers-only). **Compute cost is recorded separately from economic value**: a compute receipt never takes units. |
| V6 Contribution Units ledger | `GET /units/ledger?groupBy=identity|asset|project` (default `identity`; anything else 400 `INVALID_GROUP_BY`). The rows always add up to `totalUnits` = every unit every recorded event distributed. By asset: the units of a `KNOWLEDGE_PROVIDER` contribution go to that event's assets created by that contributor (equal parts, remainder to the first); every other unit is in the `unattributed` row. Contribution Units are an attribution primitive inside the protocol — not equity, not a promise of financial return. |

| Method | Path | |
|---|---|---|
| GET | `/identities` | `[{id, kind, displayName, wallet, ownerId, operatorId, createdAt, reputation:{acceptedOutcomes, totalUnits, firstAcceptedAt, lastAcceptedAt}}]` |
| GET | `/identities/{id}` | the same + `history:[{valueEventId, title, role, units, acceptedAt, anchorStatus}]`, newest first (one row per contribution: two roles in one event are two rows, one outcome) |
| POST | `/knowledge-assets` | `{id, version, kind, title, creatorId, contentHash, parentIds?}` → 201 / 200 |
| GET | `/knowledge-assets`, `/knowledge-assets/{id}` | `{id, version, kind, title, creatorId, creatorDisplayName, contentHash, parentIds, createdAt, usedIn:[valueEventId]}` |
| GET | `/units/ledger?groupBy=` | `{groupBy, rows:[{key, displayName, kind, totalUnits, acceptedOutcomes}], totalUnits}` |

A ValueEvent response now carries `knowledgeAssets:[{id, version, kind, title, creatorId, contentHash}]`,
`computeReceipts:[{id, providerId, providerDisplayName, node, model, inputTokens, outputTokens, gpuSeconds, estimatedCostMicroUsd, providerWallet}]`
and, on each contribution, `derivedFrom` (`null` unless the service added it). The V1 request field `computeReceipts`
(free-form strings, never produced) is replaced by the objects above.

Metrics: `pov_knowledge_assets_total`, `pov_compute_receipts_total`.

## Immediate reward (V5, KAN-822)

An **ANCHORED** ValueEvent can pay its contributors right away, in **devnet SOL** (devnet SOL stands in for stablecoin
settlement in this demo; no SPL/USDC — that would widen the signer's surface).

```
POST /value-events/{id}/distribute[?anchor=true]      RUNTIME_ADMIN · 201 new · 200 existing (idempotent) · 409 not ANCHORED / disabled
GET  /value-events/{id}/distribution                   RUNTIME_OPERATOR · 404 when there is none
```

- **Policy `pov/reward-pro-rata/v1`** (predefined, auditable, no AI): pool = `POV_REWARD_POOL_LAMPORTS` (default
  10 000 000 = 0.01 SOL); per identity, its units of every role added up, `lamports = floor(units × pool / totalUnits)` — one
  transaction per destination, the sum never exceeds the pool (the floor's remainder stays with the payer), a share that floors
  to 0 gets no payout. An identity **without wallet** is recorded `UNFUNDED` (not paid, nothing invented).
- **The same gates as any execution** (`ExecutionService.submitTransfer`, the proposal pipeline's steps without a proposal row):
  mainnet guard → fresh blockhash → on-chain simulation of the exact bytes → the payout's `(I, S)` through the deterministic
  policy (must **ALLOW**: an ESCALATE is not enough, a payout is autonomous or it does not happen) → the **independent
  validator's attestation** over those facts and bytes → the **isolated signer** (which only pays `SIGNER_ALLOWED_DESTINATIONS`
  and never more than `SIGNER_MAX_LAMPORTS` per transaction) → our own check of the signature → the signature is persisted
  (`SUBMITTED`) **before** the broadcast → confirmation poll → `CONFIRMED` with `txSignature` + `explorerUrl`.
- **The payout's facts** `(I, S)`: `strategy_id = POV_REWARD` (the policy of the service **and** of the validator must enable it:
  `CRYPTOBOT_POLICY_ENABLED_STRATEGIES=REBALANCE,POV_REWARD`, which changes `H_R` — the demo re-pins `VALIDATOR_POLICY_HASH`),
  `asset = SOL`, `trade_value` = the lamports at the oracle's SOL consensus (unknown price ⇒ DENY), slippage 0 (a transfer swaps
  nothing), `asset_exposure_after` 0 (a payout buys no asset), `daily_exposure` = payouts SUBMITTED/CONFIRMED in the last 24 h,
  `agent_permitted` = the reward is enabled, `nonce_unused` = the payout never left PENDING, expiry = now + 300 s (epoch seconds,
  like a proposal). The emergency stop (`CRYPTOBOT_EXECUTION_ENABLED=false`) stops payouts too.
- **Failures never cut the rest:** a signer refusal (`destination_not_allowed`, …), a DENY, a failed simulation or a node
  rejection makes that payout `FAILED` with the service's own error text (no secrets) and the next one goes on. A broadcast whose
  outcome is unknown, or a confirmation that did not arrive in time, stays `SUBMITTED` and is **reconciled on every read**
  (`getSignatureStatuses`) to `CONFIRMED`/`FAILED`.
- **Status** (derived from the payouts): `COMPLETE` every payout confirmed · `PARTIAL` some confirmed/submitted and some not
  (UNFUNDED included) · `FAILED` none confirmed nor submitted · `IN_PROGRESS` while one is PENDING.
- **Receipt `VALUE_DISTRIBUTION`** (Flyway V14 widens the kind CHECK): child of the event's `VALUE_EVENT` receipt, `output.hash`
  = sha256 of the canonical distribution (schema `pov/distribution/v1`: valueEventId, its receipt and hash, policy, pool,
  totalUnits and every payout — identity, wallet, units, lamports, status, tx). It enters the next Merkle batch like any receipt;
  `?anchor=true` runs the sweep right away (the same as V1). The receipt records the distribution as it ended; the rows are the
  live state.
- **Read models:** the event gains `distribution: {status, poolLamports, confirmedLamports} | null`; `GET /identities/{id}` gains
  `rewards: {confirmedLamports, payouts}`.
- **Tables (V14):** `pov_distribution` (unique per event, status, pool, policy, receipt_hash) and `pov_payout` (identity FK,
  wallet, lamports, status PENDING|SUBMITTED|CONFIRMED|FAILED|UNFUNDED, tx_signature, explorer_url, error, policy), tenant-scoped.
- **Metrics:** `pov_payouts_total{status}`, `pov_payout_lamports_total{status}`.
- **Config:** `POV_REWARD_ENABLED` (default **false**; the demo compose sets true), `POV_REWARD_POOL_LAMPORTS`,
  `POV_REWARD_STRATEGY_ID` (default `POV_REWARD`).

### Demo wallets and the signer's allowlist

`scripts/demo/wallet-devnet.sh` generates `~/.cryptobot-demo/pov-<id>.json` for `sebas` (HUMAN — so the payment to the human
shows), `dev-agent-17`, `cryptobot-001`, `review-agent-3` and `compute-node-8` **before** writing `.env.demo`, and writes
`SIGNER_ALLOWED_DESTINATIONS=<vault>,<their public keys>` (public keys only), `CRYPTOBOT_POLICY_ENABLED_STRATEGIES`, the
re-pinned `VALIDATOR_POLICY_HASH` and the `POV_REWARD_*` knobs. A stack that is already running reads the new values only when
its containers are recreated — **never `down`**:

```bash
scripts/demo/wallet-devnet.sh --no-airdrop                     # regenerates .env.demo, keeps every existing key and token
docker compose -p <project> -f docker-compose.demo.yml --env-file .env.demo up -d --no-deps cryptobot-signer
# only if CRYPTOBOT_POLICY_ENABLED_STRATEGIES / VALIDATOR_POLICY_HASH changed (first time after KAN-822):
docker compose -p <project> -f docker-compose.demo.yml --env-file .env.demo up -d --no-deps cryptobot-validator cryptobot-service
```

`scripts/demo/pov-v1.sh` ends with step 6: `distribute?anchor=true`, the payouts with tx and explorer link, and each wallet's
balance before/after (RPC `getBalance`). `--no-distribute` skips it.

## Demo: `scripts/demo/pov-v1.sh`

Against a running demo stack (`scripts/demo/run.sh --keep`, or the compose of `docker-compose.demo.yml`):

```bash
scripts/demo/pov-v1.sh                         # ./.env.demo: port + JWT_SECRET (mints a 1 h admin token, never printed)
scripts/demo/pov-v1.sh --env-file <f>          # another env file
scripts/demo/pov-v1.sh --base-url <url>        # another target, with CRYPTOBOT_DEMO_TOKEN (RUNTIME_ADMIN) in the environment
scripts/demo/pov-v1.sh --dry-run               # prints the plan and the payload
```

It seeds, idempotently, `sebas` (HUMAN), `dev-agent-17`, `cryptobot-001`, `review-agent-3` and `compute-node-8` (AGENT;
each wallet is the public key of `~/.cryptobot-demo/pov-<id>.json`, generated once with `solana-keygen new
--no-bip39-passphrase` or the same layout from python — the keypair never leaves the host), the assets
`production-acceptance-model@1` (RULESET) and `strategy-knowledge@3` (STRATEGY), and posts the example event (taskId
`KAN-819`, both assets, a compute receipt of `compute-node-8`, `commitSha = git rev-parse HEAD`, `acceptedAt` = the commit's
date so the same commit is the same event; `--accepted-at` overrides it) with `?anchor=true`. It prints `receiptHash`, `root`,
`txSignature`, `explorerUrl`, the units per contribution, knowledge and compute, the proof, the identities with their
reputation and the ledger by identity. Exit 0 only when the event is ANCHORED and `verified` is true.

## Verifying a hash on-chain by hand

1. `GET /value-events/{id}` → `canonical` is not returned, but `valueEventHash`, `artifactHash`, `acceptanceHash` are; recompute
   the last two from the `artifact` / `acceptance` objects of the response: JCS (keys sorted, no spaces) then
   `printf '%s' '<json>' | sha256sum`.
2. `GET /value-events/{id}/proof` → `receiptHash`, `anchor.proof` (siblings tagged with their side) and `root`. Fold the proof
   (`core/receipts/MerkleTree`): `leaf = SHA-256(0x00 ‖ bytes32(receiptHash))`, then per sibling
   `node = SHA-256(0x01 ‖ left ‖ right)`; the result must equal `root`. A batch of one has `root = leaf`.
3. Open `explorerUrl` (or `solana confirm -v <txSignature> --url devnet`): the memo instruction reads
   `ir/1 root=<root> n=<receipts in batch> ts=<ISO>Z`. Same root ⇒ the receipt — and through `output.hash` the ValueEvent — was
   committed on devnet at that slot.
4. `POST /anchors/{root}/verify` redoes 2 and 3 server-side and reads the transaction back from devnet at `finalized`.
