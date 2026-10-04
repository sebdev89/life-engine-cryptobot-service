# Trusted Agent Execution — Phase 0 audit

> Redacted for publication (2026-09-30): `ticket-NNN` references point to the private issue tracker; local paths are
> shown as `<workspace>/…` and lab hostnames/IPs as placeholders. The findings themselves are unchanged.

- **Scope:** `life-engine-cryptobot-service` at `origin/main` `2b69b37` (service, `signer/`, `validator/`, `programs/intent-authority`, `scripts/demo`), plus the deploy, GitOps, observability and Jira surfaces that touch it.
- **Date:** 2026-09-21 · **Issue:** ticket-584 (epic ticket-583) · **Author:** lead (three read-only auditors; raw reports in [`audit-evidence/`](audit-evidence/)).
- **Method:** evidence only. Every claim below cites `path:line` (relative to the repo at `2b69b37`), a command with its output, a Jira key that was read, or a devnet signature. "NOT FOUND" means the grep that was run returned nothing. Nothing was assumed, nothing was implemented, no environment was touched.
- **Companion documents:** [`TRUSTED-AGENT-EXECUTION-ADR.md`](TRUSTED-AGENT-EXECUTION-ADR.md) (the decision) · [`trusted-agent-execution-trust-boundaries.md`](trusted-agent-execution-trust-boundaries.md) (the diagram of what actually runs).

Verdict up front: **`READY_TO_IMPLEMENT=true`** — with the conditions in §30. CryptoBot already *is* a policy-controlled execution core with a trading vertical bolted onto the same aggregate; the migration is a separation, not a rewrite.

---

## 1. Executive summary

1. **Most of the target already exists and is verified.** Explicit 11-state machine with a transition table and a DB `CHECK` (`domain/transactions/ProposalStatus.java:36-47`, `V5:115-117`); every write is an optimistic-locked transaction that also inserts the audit event and the outbox row (`ActionProposalR2dbcStore.java:59-85`); simulation twice, the second time on the exact bytes that get signed (`ExecutionService.java:261`); an independent validator process with its own policy copy pinned by hash and an Ed25519 attestation over the transaction hash (`validator/.../ValidationService.java:194-265`); a signer process that decodes the bytes itself and signs exactly one shape — `SystemProgram.transfer` to an allow-listed destination under a lamport cap, only with a valid attestation (`signer/.../SigningPolicy.java:260-306`, `AttestationVerifier.java:92-156`); mainnet refused in three processes with separate flags, all defaulting to `false` (§6); reconciliation with the outcomes the mandate names (`MATCHED|CORRECTED|RETRIED|DEAD_LETTERED|SKIPPED`, `ReconciliationService.java:72-83`); DLQ with `resolve`/`requeue` and no blind replay; content-addressed, Ed25519-signed receipts with a provenance DAG, L1 reproduction and a Merkle anchor on devnet. Tests: **service 517/517, signer 33/33, validator 28/28** (run today, §15). Twelve confirmed wallet→vault transfers on devnet, zero failed (§14).
2. **The largest structural gap is that the canonical intent (ticket-435) is implemented but not wired.** The pipeline consumes `RebalanceIntent` (a map of target weights); the `(I)` facts the policy engine and the validator evaluate are fabricated from the plan *and from the engine's own configuration* (`PolicyEngine.policyInput`, `PolicyEngine.java:447-485`): `policyVersion = rules.version()` makes `POLICY_BOUND` tautological and `maxSlippageBps` is a config value, not the caller's constraint. ticket-457 (Backlog) already describes exactly this fix.
3. **The most concrete security gap is that the destination is not bound between approval and execution.** `prepareTransfer` reads `cryptobot.policy.rebalance-vault` at execution time (`SimulationService.java:75`); no code compares the transaction destination with the approved one (grep `destination()` in `ExecutionService`/`PolicyEngine` → 0). The only mitigation is the signer's `SIGNER_ALLOWED_DESTINATIONS`, empty by default.
4. **The validator is independent of the *process* but not of the *facts*.** It never sees the transaction bytes or the chain; it attests a `messageHash` the service tells it, over `(I,S)` facts the service recorded at proposal time, including a frozen `current_slot` (`ValidatorClient.java:299-301`, `PolicyEngine.java:463-485`). Removing it *would* change security properties (§9), so it is kept and hardened, not redesigned.
5. **Persistence proves less than the code claims.** `messageHash` and the attestation payload are not persisted (`ExecutionService.java:328-331`); the `EXECUTION` receipt does not carry validator, attestation, `policyHash` or `submittedAt/confirmedAt/reconciledAt` (`Receipts.java:378-388`); `ActionProposalR2dbcStore.commit` and the outbox `SKIP LOCKED` query have **no test against a real Postgres** (Testcontainers is not in `pom.xml`).
6. **Operations are live and consistent but under-pinned.** uat-compose and k8s-uat run all three modules at digest `sha-2b69b37` (Argo `Synced/Healthy`), PROD does not run CryptoBot (`deployments/prod-current.yml` → NOT FOUND). But `VALIDATOR_POLICY_HASH` is empty in both UATs, the wallet private key reaches the signer as an environment variable in uat-compose, k8s-uat does not smoke CryptoBot, and the devnet smoke result is not written to `ops.d/evidence`.
7. **The hackathon golden path in the mandate ("Buy 50 USDC of SOL") is not what the system can execute.** The only executable operation is `REBALANCE` → one `SystemProgram.transfer` SOL→vault (`SimulationService.java:49-53,70-81`; pitch §7). A swap needs versioned transactions, lookup tables and a DEX route the byte-level signer cannot bound. Decision (§27): keep the executable leg, narrate the intent; swap is post-12/10.
8. **Jira already has the epic** (ticket-583, ticket-584 — created 2026-09-21 05:58, no other "Trusted" issue exists) and several stories the phases should *adopt* instead of duplicating: ticket-457 (phase 2), ticket-466 (phase 4), ticket-500 (phase 6, PR #27 blocked by a `V10` migration collision). ticket-493, ticket-501, ticket-439 are open while their code is in `main` and in UAT; ticket-455 is already covered by ticket-573.
9. **Freeze:** hackathon code freeze 08/10, submission ≤ 10/10 (vault `Products/CryptoBot-Hackathon-Demo-Path-2026-09-20.md §7`). Phases 1–2 are internal and additive and can land before the freeze if `scripts/demo/run.sh` and the metric names stay green; phases 3–7 start 13/10.

## 2. Existing architecture

One Spring Boot service (`src/`, 266 main files / 23 260 lines; 105 test files / 17 817 lines; 10 Flyway migrations) plus two small Spring Boot processes (`signer/`, `validator/`), a native Solana program (`programs/intent-authority`, not deployed), an Angular UI (separate repo), and Life Engine Auth/Runtime as dependencies.

| Layer (today) | Packages | What it really does | Evidence |
|---|---|---|---|
| Control-plane API | `api/controlplane/*` | REST for wallets, proposals, receipts, lineage, anchors, dead letters, chaos. Identity always from the JWT (`Principals.java:16-19`). | §22 |
| Execution pipeline | `application/controlplane/{ProposalService, ExecutionService, PolicyEngine, SimulationService, RiskEngine, RebalancePlanner, AuditService, Receipts, ExecutionReceipts}` | plan → risk → simulate → policy → human approval → timelock → validator → signer → broadcast → confirm → receipt | §3 |
| Reliability | `application/reliability/*`, `domain/reliability/*` | outbox (`SKIP LOCKED`), reconciliation sweep, idempotent retry, DLQ resolve/requeue | §10, §11 |
| Receipts | `domain/receipt/*`, `application/receipt/*` | JCS canonicalisation, domain-separated SHA-256, Ed25519 signature, provenance edges, L1 reproduction, Merkle anchor | §12 |
| Policy / intent / risk (domain) | `domain/policy`, `domain/intent`, `domain/risk`, `domain/oracle` | deterministic policy `(I,S,R_v)→verdict`; canonical `TradingIntent` (unwired); portfolio risk engine; multi-source price consensus | §7 |
| Trading vertical | `domain/strategy`, `domain/portfolio`, `domain/advisor`, `application/{oracle,quotes,MarketReview*,Monitoring*,Watchlist…}`, `adapters/marketdata`, `adapters/quotes`, `infrastructure/{snapshot,binance,runtime}`, `api/*` (root) | rebalance planner, portfolio valuation, LLM advisor via Runtime, ARS quotes, legacy market-review workflow | §21 |
| Chain adapter | `adapters/solana/*` | hand-written JSON-RPC client (no Solana library), legacy transaction encoder, System/Memo programs, client for the intent-authority program | §14 |
| Trust processes | `signer/`, `validator/` | separate JVMs, own tokens, own keys; signer has no RPC and no DB | §8, §9 |
| Persistence | `infrastructure/persistence/controlplane/*` (R2DBC) | one aggregate `action_proposal` with a JSONB `doc`, `audit_event`, `outbox_event`, `dead_letter`, `intelligence_receipt`, `receipt_edge`, `receipt_anchor*`, `deterministic_inference` | §24 |

Dependency direction violations (measured, `audit-evidence/…A…md §1`): `domain/wallet/Wallet.java:3` imports `adapters.solana.SolanaCluster`; `application/*` imports 7 classes of `adapters.solana` and both `integration/*` clients; `api/controlplane/WalletsController.java:3,74` calls `SolanaRpcClient` directly; `ProposalsController.java:10-11` reads repositories directly. Ports `ChainExecutionPort`, `ChainSimulationPort`, `ChainObservationPort`, `AssetPort`: **NOT FOUND** (`grep -rn "Port\b" src/main/java` → only `ArsQuotesPort`, `MarketSnapshotProvider`, `PriceProvider`, `OutboxSink`).

## 3. Existing execution sequence

Verified step by step in `audit-evidence/2026-09-21-A-execution-core.md §2` and `…B… §3.1`. Condensed:

```
POST /api/cryptobot/wallets/{id}/proposals  {kind:REBALANCE, targetWeights, counterAsset}     WalletsController.java:96-110
  ProposalService.createRebalance :143-197
    RebalancePlanner.plan → RiskEngine.evaluate (portfolio risk; WARN only, never blocks :153-158)
    insert PROPOSED → audit PROPOSAL_CREATED → receipts STRATEGY, RISK_DECISION
    simulate :207-222 → SimulationService.simulate :45-68  (economic + simulateTransaction sigVerify=false on a fresh blockhash)
    evaluatePolicy :230-300 → oracle.read + wallet history (cooldown/24h exposure) + signer/validator identity
                            → PolicyEngine.evaluate :177-289 (21 named rules) + DeterministicPolicyEngine (11 predicates, R_v, H_R)
                            → AWAITING_APPROVAL | BLOCKED_BY_POLICY ; audit POLICY_EVALUATED ; outbox trade.requested ; Decision Receipt (kind RISK_DECISION, engine policy-engine)
POST /proposals/{id}/approve|reject|cancel                                                    ProposalService.decide :310-336
    timelock by tier (ALLOW → 0 s, ESCALATE → 30 min) ; expiresAt pushed ; audit + outbox
POST /proposals/{id}/execute  (Idempotency-Key header | body operationId | random UUID)       ProposalsController.java:108-127
  ExecutionService.doExecute :138-172
    same operationId → suppressDuplicate ; in flight with another → 409
    executionPreconditions :505-544 (APPROVED, kill switch, executable, verdict≠DENY, rules.hash()==verdict.policyHash, timelock elapsed, not expired)
    fresh oracle read + priceViolations ; requireClusterAllowed (mainnet gate #1, wallet AND proposal cluster)
    start :232-239  APPROVED→EXECUTING with operation_id (unique index)                     ← idempotency bound here, before signing
    run :252-285
      1 prepareTransfer (fresh blockhash; destination = config rebalance-vault)               SimulationService.java:71-81
      2 simulateTransaction on the exact bytes (replaceRecentBlockhash=true)                  :261
      3 validator.authorize (I,S recorded at proposal time, H_R, verdictHash, SHA-256(message), cluster) → attestation   ValidatorClient.java:114-176
      4 signer.sign(unsigned wire, feePayer, cluster, attestation)                            SignerClient.java:89-116
      5 verifySigned: same message bytes, Ed25519 verify against the wallet pubkey            :288-305
      6 persistSigned: ExecutionRecord SIGNED + audit EXECUTION_VALIDATED, EXECUTION_SIGNED   :307-335   ← signature persisted BEFORE broadcast
    broadcast :337-364  sendTransaction (mainnet gate #2, SolanaRpcClient.java:214-218) → SUBMITTED → confirm (20 × 1.5 s) → EXECUTED | FAILED | stays SUBMITTED
    broadcastFailed :372-391  JSON-RPC error → FAILED ; anything else → EXECUTION_BROADCAST_UNCERTAIN (row keeps the signature)
ReconciliationJob (startup + every 30 s)  ReconciliationService.sweep :116-136
    Confirmed → EXECUTED (+receipt) ; Failed → FAILED ; Expired & never seen → retry same operationId (≤2) else DLQ retries_exhausted ; Pending ×20 → DLQ ambiguous
```

What the sequence does **not** do: bind the destination approved to the destination executed (§17); persist the execution-time simulation result or the attestation payload; use a nonce from the intent (`nonceUnused` is derived from the row state, `PolicyEngine.java:474-475`); emit `receipt.created` (constant exists, never emitted).

## 4. Repository and component inventory

| Component | Where | State | Digest / commit |
|---|---|---|---|
| `life-engine-cryptobot-service` (service) | GHCR `life-engine-cryptobot-service` | UAT compose + k8s-uat | `sha-2b69b37` = `sha256:ec067f0d…` (`deployments/uat-current.yml:83-118`) |
| signer | GHCR `life-engine-cryptobot-signer` | idem | `sha256:eddc16c5…` |
| validator | GHCR `life-engine-cryptobot-validator` | idem | `sha256:de50563a…` |
| `programs/intent-authority` | Rust, native (no Anchor) | tested in CI (`ci.yml:70-97`), **not deployed, not wired** (`grep -rn IntentAuthorityProgram src/main` → only its own package) | — |
| `life-engine-cryptobot-ui` | Angular | `origin/main b439995` (`/live`, lineage, DLQ, chaos) | — |
| Runtime workflows | `crypto.market-review.v1` exists; `crypto.portfolio-advisor.v1` **NOT FOUND in runtime main** (PR runtime #33 open since 2026-09-14) | `/wallets/{id}/ask` fails in UAT | ticket-326 |
| CI | `.github/workflows/ci.yml`: build+test (service, signer, validator, `scripts/demo/tests/test-run.sh`), `program-test` (cargo), `docker-publish` matrix ×3 on push to main (ticket-579) | last main run `35576296231` success | `cancel-in-progress` on main leaves commits without an image (d60fc53, 9d68b84) |
| Open PRs | #30 ticket-582 (metrics, MERGEABLE) · #27 ticket-500 (E2E JVM, **`V10` collides with `V10__inference_engine_version_width.sql`**) · #26 ticket-353 (glossary, `post-v1`, conflicts) | — | — |
| Stale branches / worktrees | `origin/ticket-403-…`, `origin/ticket-199-…` (merged by squash); worktree `ticket-439-cryptobot` with 62 obsolete staged files; 8 merged worktrees under `cryptobot-service/.worktrees`; main checkout on `ci/mvnw-self-hosted` with 43 dirty files | hygiene only | — |
| Deploy | `deploy/uat/docker-compose.uat.yml` (networks `le-uat-cryptobot internal:true`, `le-uat-egress`), `platform-catalog.yml:105-143` (ticket-388 done), `uat/smoke-cryptobot-devnet.sh`, `uat/scripts/cryptobot-devnet-credentials.sh` | — | PROD: cryptobot absent |
| GitOps | `base/cryptobot{,-signer,-validator}`, `overlays/uat/cryptobot/*` (3 SOPS secrets), one Argo `Application cryptobot`, 6 NetworkPolicies | `Synced Healthy rev e10ed1d` | `VALIDATOR_POLICY_HASH=` empty (`cryptobot-validator.env:19`) |
| Observability | dashboards `life-engine-cryptobot-demo.json` (51 panels), `-negocio.json` (31), rules `cryptobot.yml` (3 alerts, 26 rule tests) | — | signer/validator not scraped in uat-compose (no `life-engine.scrape` label, compose `:986-988`) |

Full tables: `audit-evidence/2026-09-21-C-operations-compatibility.md §1-§4`.

## 5. State machine today

`ProposalStatus` (`domain/transactions/ProposalStatus.java:14-48`), 11 values, explicit `canTransitionTo` switch, `IllegalStateException` on anything else (`ActionProposal.withStatus`, `:48-56`), `CHECK chk_action_proposal_status` in `V5:115-117`:

```
PROPOSED ──simulate──▶ SIMULATED ──policy──┬─▶ AWAITING_APPROVAL ──approve──▶ APPROVED ──execute──▶ EXECUTING ──send──▶ SUBMITTED ──confirm──▶ EXECUTED
    │                                      └─▶ BLOCKED_BY_POLICY        │  │                          │  ▲                │
    └─▶ FAILED                                              reject ◀────┘  └──cancel──▶ REJECTED      │  └── retry (ticket-571, same operationId)
                                                     expire ◀── EXPIRED ◀── expire                  FAILED ◀────────────┴── pre-broadcast / RPC reject / on-chain error / reconciler
```

- Terminal: `BLOCKED_BY_POLICY, REJECTED, EXECUTED, FAILED, EXPIRED`. In flight: `EXECUTING, SUBMITTED`.
- Sub-states in JSONB, **without CHECK**: `ExecutionRecord.status ∈ {SIGNED, SUBMITTED, EXECUTED, FAILED}` (`ExecutionRecord.java:42-45`); `reconciledAt`, `retries`, `previousSignature`, `reconciliationAttempts` in `doc.execution`.
- Other enums with DB constraints: `OutboxEvent.Status {PENDING, PUBLISHED, FAILED}` (`V5:143`), `DeadLetter.Source {OUTBOX, RECONCILIATION}` (`V5:165`), `DeadLetter.Outcome {RESOLVED, REQUEUED}` (`V9:16`), `ReceiptAnchor.status {PENDING, SUBMITTED, FINALIZED, FAILED, ABANDONED}` (`V8:147`).
- Policy verdicts: `ALLOW | DENY | ESCALATE` with `Escalation {NONE, REQUIRE_SECOND_AGENT, REQUIRE_HUMAN_SIGNATURE}` and `AutonomyTier` (`PolicyVerdict.java`).
- Every transition goes through `ActionProposalRepository.commit(ProposalTransition)`: `UPDATE … WHERE id AND owner_user_id AND version = :expected AND status = :expectedStatus` + audit insert + outbox insert in one transaction; 0 rows → `StaleProposal` (409); unique violation → `DuplicateOperation` (`ActionProposalR2dbcStore.java:59-85`).

**`SUBMITTED ≠ EXECUTED ≠ reconciled` already holds** (§12 of the mandate): `EXECUTED` is set by `confirm` or by the reconciler; `reconciledAt` is a separate field.

## 6. Security boundaries today

| Boundary | Where trust changes | Enforced by | Evidence |
|---|---|---|---|
| Internet → service | JWT from Life Engine Auth (HS256 ≥32 bytes or RS256/JWKS); `CRYPTOBOT_SECURITY_ENABLED=true` in UAT | `CryptobotJwtService.java:24-66`, `CryptobotSecurityConfig.java:49-80` | all `/api/cryptobot/**` = `RUNTIME_OPERATOR`; anchors/dead-letters/demo = `RUNTIME_ADMIN`; `anyExchange().denyAll()` |
| LLM (Runtime) → service | the advisor output is text/JSON + `runtimeRunId`; it **cannot create or execute proposals** | `AdvisorService.java:36-44,88-135`; proposals are created by a human JWT (`WalletsController.java:96-107`) | open question P-1: the role the S2S token `sub=service:cryptobot` derives in Auth |
| service → validator | shared static token `X-Validator-Token`, internal network `le-uat-cryptobot` (`internal: true`) | `ValidatorController.java:51-75,83-89` | validator has no RPC, no DB |
| service → signer | shared static token `X-Signer-Token`, same internal network; the signer decodes bytes itself and trusts nothing the caller says | `SignerController.java:55-100,144-150`; `SigningPolicy.java:194` | signer has no RPC, no DB (`SignerApplication.java:85-89`), runs non-root uid 10002 |
| validator → signer | Ed25519 attestation over the canonical payload `{proposal_id, message_hash, cluster, policy_hash, input_hash, verdict_hash, decision, escalation, validator, issued_at, expires_at}`; signer pins `SIGNER_VALIDATOR_PUBLIC_KEY`; without a pinned key **nothing is signed** | `ValidationService.java:233-247`; `AttestationVerifier.java:92-156` | tests `withoutTheValidatorNothingIsSigned`, `failsClosedWithoutAValidatorKey` |
| signer → chain | the signer never broadcasts; the service does (`ExecutionService.java:339`) | — | the private key never leaves the signer process |
| Mainnet | flags default `false` in **three processes**: `CRYPTOBOT_ALLOW_MAINNET` (`ExecutionProperties.java:13-24`, checked in `ExecutionService.java:179-196` and `SolanaRpcClient.java:214-218`), policy rule `EXECUTION_CLUSTER` with `execution-cluster: devnet` hard-coded (`application.yml:203`), validator attests `cluster`, signer `SIGNER_ALLOW_MAINNET` + `cluster_mismatch` + `attestation_cluster_mismatch` (`SigningPolicy.java:314-329`); UAT smoke reads both flags from the containers (`smoke-uat.sh:751-759`) | — | ticket-493 merged (PR #20) — Jira still "Revisar" |
| Secrets | none in git (grep for 64-byte JSON arrays and 87-88-char base58 across `git ls-files` → 0; only test vectors guarded by `DevSecretNotShippedTest`); no `log.*` of tokens/keys; `include-message: never` | `audit-evidence/…B… §5.3` | **UAT: wallet key as env var** `SIGNER_KEYPAIR_JSON` (`docker-compose.uat.yml:1063`) from a plain `.env.uat` (0600); demo mounts a file `:ro`; SOPS for these values: NOT FOUND in `deploy` |
| Separation of duties | **NOT FOUND**: the same `RUNTIME_OPERATOR` proposes, approves and executes; `decide` does not compare `actor` with `requestedBy` (`ProposalService.java:310-338`); `ESCALATE` only adds a 30-min timelock and the signer accepts `ESCALATE` as authorising (`AttestationVerifier.java:42`) | — | ticket-466 (Backlog) already proposes the second validator / human signature |

## 7. Policy

Two engines, one adapter:

- **`application/controlplane/PolicyEngine`** — 21 named rules in code (`PolicyEngine.java:66-115`): blocking `ASSET_ALLOWLIST, MAX_TRADE_USD, MAX_TRADE_PCT_OF_PORTFOLIO, COOLDOWN, PRICE_QUORUM, PRICE_STALE, PRICE_DEVIATION, PRICE_CIRCUIT_BREAKER, PRICE_DRIFT, AUTHORIZATION`; execution-only (`executable=false`, proposal still approvable as a paper trade) `EXECUTION_ENABLED, EXECUTION_CLUSTER, REBALANCE_VAULT_CONFIGURED, MAX_LAMPORTS_PER_TX, ONCHAIN_SIMULATION_PASSED, SIGNER_CONTROLS_WALLET, SIGNER_DESTINATION_ALLOWLISTED, SIGNER_MAX_LAMPORTS, SIGNER_CLUSTER, VALIDATOR_AVAILABLE`; `TIMELOCK_ELAPSED` only in `executionPreconditions`. Thresholds from `cryptobot.policy.*` (`application.yml:200-236`).
- **`domain/policy/DeterministicPolicyEngine`** (ticket-436) — 11 predicates in fixed order, no short-circuit, `Unknown ⇒ Deny` (`PolicyPredicate.java:13-33`, `DeterministicPolicyEngine.java:42-131`): `POLICY_BOUND, ASSET_ALLOWED, TRADE_WITHIN_MAX, DAILY_LIMIT, ASSET_CONCENTRATION, SLIPPAGE_WITHIN_MAX, ORACLE_FRESH, AGENT_PERMITTED, STRATEGY_ENABLED, NONCE_UNUSED, NOT_EXPIRED`. Decision `ALLOW | DENY | ESCALATE` by value tier. **Policy is data**: `PolicyRules` record with `version` and integer limits, `canonicalJson()`/`hash()` = `H_R` (`PolicyRules.java:25-84`), loaded from YAML/env at boot (`AuthorizationProperties.java:76-88`); non-monotonic tiers refuse to start.
- **Persisted per evaluation**: `PolicyVerdict{decision, escalation, tier, failedPredicates, evaluatedPredicates, policyVersion, policyHash, inputHash}` + `hash()` inside `PolicyDecision{allowed, executable, violations[rule,message], executionViolations, rulesApplied, evaluatedAt, authorization, input(I,S), oracle}` in the JSONB doc; audit `POLICY_EVALUATED` with `policyVersion, policyHash, inputHash, verdictHash, failedPredicates, rulesApplied`; a Decision Receipt L1 that `verify` re-runs (`Receipts.java:198-263`). Execution refuses a proposal decided under another `H_R` (`PolicyEngine.java:521-523`).
- **What is missing vs §6 of the mandate**: no `policy_evaluation` table (JSONB + audit + receipt instead — acceptable); no admin/version endpoint (change = redeploy); `matchedRules` ≈ `rulesApplied` + `violations` and `reasonCodes` ≈ `failedPredicates` (naming only); **the `(I)` input is not the caller's intent** (`policyVersion = rules.version()`, `maxSlippageBps = executor-slippage-bps`, `agentId = requestedBy` e-mail, `validUntilSlot = expiresAt.epochSecond`) — `POLICY_BOUND` and `SLIPPAGE_WITHIN_MAX` verify nothing that came from the producer; the policy Decision Receipt is stored as `kind=RISK_DECISION` because `chk_intelligence_receipt_kind` (`V6:42-44`) has no `POLICY_DECISION`.
- **Trading risk vs execution policy (§18)**: separated in code (`domain/risk` never blocks, `ProposalService.java:153-158`), but `ASSET_CONCENTRATION` and `DAILY_LIMIT` — portfolio concepts — live inside the execution policy engine (`DeterministicPolicyEngine.java:112-114`). This is the seam to cut in phase 3.

## 8. Signer

`signer/` — separate Spring Boot JVM, Ed25519 from the JDK (JEP 339), no external crypto library, no RPC, no DB. Full table in `audit-evidence/…B… §1`.

| Requirement §8 | State | Evidence |
|---|---|---|
| High-value boundary, only process with the key | **yes** — key loaded once from `SIGNER_KEYPAIR_PATH` (file) or `SIGNER_KEYPAIR_JSON` (env), only the pubkey is logged | `SignerKeyStore.java:246-281,230-231` |
| No arbitrary sign endpoint | **yes** — `POST /api/signer/sign` accepts one shape: 1 signature, fee payer == own key, exactly 1 instruction, program == System, `transfer`, source == fee payer, destination ∈ `SIGNER_ALLOWED_DESTINATIONS` (empty list ⇒ refuse), lamports ≤ `SIGNER_MAX_LAMPORTS` (default 2 SOL), cluster == `SIGNER_CLUSTER` | `SigningPolicy.java:260-306`; `SignerController.java:64-100` |
| Accepts only validated artefacts | **yes for transfers** — attestation required (`SIGNER_REQUIRE_ATTESTATION=true`; `false` only under Spring profile `local`/`test`, otherwise the process refuses to start: `AttestationRequirementGuard.java:186-207`), validator key pinned, signature verified over the exact canonical payload, `proposal_id`, `message_hash == SHA-256(decoded message)`, `decision ∈ {ALLOW, ESCALATE}`, `[issued_at, expires_at] ± 30 s`, `cluster` present and equal | `AttestationVerifier.java:92-156` |
| Second shape: anchor memo | `POST /api/signer/sign-anchor` — devnet only, 1 Memo instruction, zero accounts, text must match `^ir/1 root=(sha256:…) n=… ts=…Z$` and the request's root/count; **no attestation, no rate limit** (fee drain 5 000 lamports per call, severity L) | `SigningPolicy.evaluateAnchor:212-254`; `SignerController.java:106-126` |
| Verifies network | cluster canonical (`devnet`/`mainnet-beta`), mainnet needs `SIGNER_ALLOW_MAINNET=true` (default `false`) **and** the attestation's cluster; **genesis hash: NOT FOUND** (`grep -rni genesis signer/src validator/src src/main` → 0) — the `cluster` label is declarative | `SigningPolicy.java:314-329` |
| Scope / reuse / expiry | expiry yes; reuse cache **NOT FOUND** (harmless: Ed25519 is deterministic, same bytes ⇒ same signature ⇒ same tx id); **velocity / daily cap NOT FOUND** (`grep -rniE "velocity|rate.?limit|daily" signer/src/main` → 0) | `AttestationVerifier.java:136-154` |
| Audit event per attempt | JSON log events `auth_rejected`, `sign_refused` (with reason), `signed` (proposalId, lamports, destination, signature, validator, decision, verdictHash), anchor events; **no own Micrometer counter** (only actuator defaults) — PR #30 (ticket-582) adds `SignerMetrics` | `SignerController.java:77-98,118-130` |
| Auth | static token `X-Signer-Token`, constant-time compare, empty token ⇒ 401; internal network only, no `ports:` | `SignerController.java:144-150`; compose `:1078-1081` |
| Key delivery | demo: file mounted `:ro` (`docker-compose.demo.yml:66,76`); **uat-compose: env var** (`docker-compose.uat.yml:1063`); k8s-uat: `secretKeyRef` from SOPS `cryptobot-signer-secret` | — |

"What can a compromised service make the signer sign?" (`…B… §1.7`): transfers `wallet → vault ≤ 2 SOL` on the configured cluster, as many times as it wants (no velocity), only with a valid attestation for those exact bytes; anchor memos without limit; bytes carrying a mainnet blockhash labelled `devnet` (no genesis check) — impact bounded to `cap × N` to the operator's own vault. It **cannot** change destination, program, signer count, or sign SPL/Token-2022/swap/`AdvanceNonce`/ComputeBudget instructions.

## 9. Validator

`validator/` — separate JVM, own copy of `R_v` (`validator/src/main/resources/application.yml:243-256`, env `VALIDATOR_POLICY_*`), optional pin `VALIDATOR_POLICY_HASH` (mismatch ⇒ refuses to start, `PolicyStore.java:28-31`), independent hand-inlined predicate table (`IndependentPolicyTable.java:16-19`), shared test vectors with the service (`policy/vectors-v1.json`, `DefaultPolicyHashParityTest`).

| Requirement §9 | Verified independently? | Evidence |
|---|---|---|
| Intent integrity | **partial** — re-hashes canonical `(I,S)` and re-derives the verdict; disagreement with `expectedVerdictHash` ⇒ `VERDICT_DISAGREEMENT` ⇒ DENY | `ValidationService.java:215-228` |
| Policy decision + revision | **yes** — `policyHash` must equal its own `H_R` | `:221-223` |
| Simulation | **no** — NOT FOUND (`grep -rn simulat validator/src/main` → 0) | — |
| Transaction message | **no** — attests the `messageHash` the caller supplies; never decodes bytes | `:202-204,236` |
| Amount / mint / destination / programs | **no** — only `trade_value_cents` and `asset` (symbol) as *declared* facts | `IndependentPolicyTable.java:42-44` |
| Network | **yes** — `cluster` required, canonical, attested verbatim (but not tied to the bytes) | `:210-213,237` |
| Slippage | predicate on a value the *service* sets from config | `PolicyEngine.java:466` |
| Expiry | `current_slot ≤ valid_until_slot` on **epoch seconds frozen at proposal time** by the caller — no own clock | `PolicyEngine.policyInput:463,485`; `ValidatorClient.java:299-301` |
| Nonce / idempotency | `nonce_unused` as a declared fact | `PolicyEngine.java:474-475` |
| Attestation | produces Ed25519 over canonical JSON, TTL 90 s (`VALIDATOR_ATTESTATION_TTL`); key from `VALIDATOR_KEYPAIR_PATH|JSON`, **ephemeral with WARN if absent** | `ValidationService.java:233-247`; `AttestationKeyStore.java:122-141` |
| Fail-closed | disabled ⇒ DENY; unknown fact ⇒ predicate fails; malformed ⇒ 400 without attestation; policy that does not load ⇒ no start | tests `unknownFactsDeny`, `disabledValidatorDeniesEverything` |
| Pinned in UAT? | **no** — `VALIDATOR_POLICY_HASH: ${CRYPTOBOT_POLICY_HASH:-}` (`docker-compose.uat.yml:1020`), `.env.uat` has no `CRYPTOBOT_POLICY_HASH`; k8s `cryptobot-validator.env:19` empty; demo compose pins (`policy pin sha256:1882cd0a…`) | — |

**"If removing it changes no security property, redesign it" (§9).** Removing it changes three properties: (a) `POLICY_HASH_MISMATCH` against a policy pinned in another process; (b) re-derivation by an independent implementation (catches bugs/alterations in `DeterministicPolicyEngine`); (c) the attestation↔bytes↔cluster↔90 s binding the signer demands — with `SIGNER_REQUIRE_ATTESTATION=true` and no validator **nothing is signed** (tests above). It does **not** change lamport cap, destination allow-list, program, fee payer or mainnet-closed (the signer enforces those by bytes). Conclusion: keep it; make it the barrier the mandate describes by (1) receiving the unsigned transaction and decoding it (reuse `LegacyMessageDecoder` from the signer's copy), (2) crossing lamports/destination/program with `(I,S)` and its own allow-lists, (3) using its own clock for `NOT_EXPIRED`, (4) including `approval_hash` in the attestation. Also: `ValidatorClient.check` compares `message_hash`/`cluster` by `String.contains` over the payload (`ValidatorClient.java:169-174,329-334`) — works because the JSON is canonical, but should parse.

## 10. Reconciliation

`application/reliability/ReconciliationService` (+ `ReconciliationJob` at startup and every `interval=30s`; `grace=2m`, `batchSize=100`, `maxAttempts=20`, `maxRetries=2`; `ReliabilityProperties.java:47-57`).

- Scope: rows in `EXECUTING`/`SUBMITTED` older than the grace period (`findInFlight`, `:116-136`, partial index `in_flight` in `V5`).
- Verdict from the chain (`:206-233`): `getSignatureStatuses(searchTransactionHistory=true)` → `Confirmed | Failed | Pending`, plus `Expired` when unseen and `getBlockHeight > lastValidBlockHeight` (two independent reads).
- Outcomes (`Result`, `:72-83`): **`MATCHED | CORRECTED | RETRIED | DEAD_LETTERED | SKIPPED`** — identical to the mandate's names (§13), kept. Metric `cryptobot_reconciliation_total{outcome}` (+ legacy `trade_reconciled_total{result}`).
- Actions: Confirmed ⇒ `EXECUTED` + audit `RECONCILED` + outbox `trade.confirmed{reconciled:true}` + `EXECUTION` receipt; Failed ⇒ `FAILED`; Expired & never seen ⇒ `ExecutionService.retry` with the **same `operationId`** (fresh blockhash, new attestation, new signature, `EXECUTION_RETRIED`) up to `maxRetries`, then DLQ `retries_exhausted`; Pending after `maxAttempts` sweeps ⇒ DLQ `ambiguous`; row without signature ⇒ `FAILED` (never retried) or DLQ `inconsistent`.
- Repeatable: every write is version-guarded ⇒ a second reconciler gets `StaleProposal` ⇒ `SKIPPED` (test `retryIsGuardedByTheVersion`). Caveat: in `retry` the validator and signer are called *before* the guarded commit (`run :268-281`), so two concurrent reconcilers could obtain two signatures; only one is persisted and broadcast (the other dies in `persistSigned` → `StaleProposal`). No double execution; one orphan signature in the signer log.
- Not persisted: `slot`/`blockTime` of the observation; the outcome itself lives only in the metric and the audit event.

Real evidence of the recovery path: chaos `rpc-down` run 2026-09-20 — signature #1 never seen → dead letter `ambiguous` → `requeue` → `RETRIED` → signature #2 `2mU1LTY3…` confirmed at slot 501489762, vault transfers 1→2, DLQ open = 0 (vault `Products/evidencia/cryptobot-e2e-devnet-20260920-chaos-rpc-down.md`).

## 11. DLQ and retry

- **Outbox** (`OutboxPublisher.java`): tick 2 s, batch 50, `FOR UPDATE SKIP LOCKED` (`OutboxEventR2dbcStore.java:54-65`), `maxAttempts=8`, backoff `1 s × 2^(n-1)` capped at 5 min, **no jitter** (`grep -n jitter application/reliability` → 0); exhausted ⇒ `FAILED` + `DeadLetter(OUTBOX)`. Events `trade.requested|approved|rejected|expired|cancelled|submitted|confirmed|failed` (`TradeEvents.java:20-28`); `receipt.created` reserved, never emitted. Only sink today: `LoggingOutboxSink` (contract requires idempotency by `event.id`).
- **Dead letters** (`V5:151-173`, `V9`): `dead_letter(id, source, ref_id, proposal_id, owner_user_id, reason, payload, created_at, resolved_at, resolved_by, resolution, outcome)`; kinds `ambiguous | retries_exhausted | inconsistent | outbox`. API (`DeadLettersController.java:41-74`, `RUNTIME_ADMIN`): `GET /dead-letters[?resolved&source&proposalId&limit&offset]`, `GET /{id}`, `POST /{id}/resolve` (settles to what the chain proves; 409 without a verdict), `POST /{id}/requeue` (outbox event back to `PENDING`, or reconcile-now with the idempotent retry); one resolution per letter (`UPDATE … WHERE resolved_at IS NULL`, `DeadLetterR2dbcStore.java:99-100`); audit `DEAD_LETTER_RESOLVED|REQUEUED`; metrics `cryptobot_dead_letter_total{reason}`, `cryptobot_dead_letter_open`; runbook `docs/runbooks/dead-letter.md`.
- **Blind replay: not possible.** `resolve` never re-sends; `requeue` only re-runs a reconciliation whose retry requires a chain-proven `Expired` verdict and is bounded by `maxRetries` and the row version.
- **Failure taxonomy `TRANSIENT/PERMANENT/POLICY/SECURITY/CHAIN/DEPENDENCY/UNKNOWN` (§14): NOT FOUND** (`grep -rn "TRANSIENT\|PERMANENT" src/main/java` → 0). What exists instead: `CryptobotMetrics.FailureStage {PREFLIGHT, VALIDATE, SIGN, BROADCAST, ONCHAIN, RPC, OTHER}` (`:116-127`), `ErrorCode` `CB-POLICY/RISK/EXEC/SOLANA/RECON/DLQ/RUNTIME/AUTH/HTTP-*` (`ErrorCode.java:21-78`), the implicit split in `broadcastFailed` (JSON-RPC error = permanent, everything else = uncertain) and `Verdict {Confirmed, Failed, Expired, Pending}`. A signer outage today is `FAILED` at stage `SIGN` — terminal, safe, **not retried** (§27, failure demo 7).

## 12. Receipt model

Table `intelligence_receipt` (`V6:19-53`): `receipt_hash PK`, `tenant_id`, `owner_id`, `kind` (CHECK: `WALLET_SNAPSHOT, HUMAN_IDEA, MARKET_ANALYSIS, RISK_DECISION, STRATEGY, SIMULATION, EXECUTION, PROJECT_ANALYSIS`), `schema_version` (`ir/1`), `nonce` (UNIQUE per tenant), `wallet_id`, `proposal_id`, `body JSONB`, `canonical BYTEA`, `signature BYTEA`, `key_id`, `reproducibility` (L0..L3), `anchor_*` (outside the hash), `created_at`; `receipt_edge(child, parent, role ∈ {DERIVES_FROM, VALIDATES, EXECUTES, REUSES})`; `deterministic_inference` (`V7`); `receipt_anchor`, `receipt_anchor_member` (`V8`).

- Hash: `SHA-256("life-engine.cryptobot.receipt" ‖ 0x00 ‖ JCS(body))`; signature: Ed25519 over `"life-engine.cryptobot.receipt.sig" ‖ 0x00 ‖ hash` (`ReceiptCanonicalizer.java:21-56`); vectors pinned in `src/test/resources/receipt/vectors-v1.json`. Changing the domain string invalidates every receipt issued and the anchor already on devnet (memo `ngrN7DP…`, n=25, slot 501505553).
- Key: `CRYPTOBOT_RECEIPT_SIGNING_KEY` in the **service** process (`ReceiptKeyConfig.java:24-37`); **ephemeral with WARN if absent**; `verify` accepts only the current `keyId` (`ReceiptService.java:245`) — no keyring, so rotation makes history "invalid".
- Verification without trusting the UI: `GET /receipts/signing-key` (public key, domains, "RFC 8785") + `POST /receipts/{hash}/verify` → `{hashMatchesCanonical, bodyMatchesCanonical, signatureValid, keyId, parentsPresent, level, reproduced, valid, anchor{proofValid}}`. Missing: **`POST /receipts/verify` with the receipt in the body** — today `verify` only accepts a hash that is in the DB, so a receipt handed to a third party cannot be checked against a tampered copy (failure demo 10 is test-only: `ReceiptServiceTest.java:111-132`).
- Anchor (ticket-394): Merkle root of a batch → memo `ir/1 root=… n=… ts=…` signed by the signer's `sign-anchor` (devnet only, memo re-derived from bytes) → wait `finalized` → stamp `anchor{chain, tx, slot, root, proof}`; idempotent re-anchor per root; `POST /anchors/{root}/verify` reads the memo back from the chain. Job off by default (`cryptobot.anchor.enabled=false`).
- `EXECUTION` receipt today (`Receipts.execution`, `:353-398`): parents `SIMULATION` (DERIVES_FROM) + `STRATEGY` (EXECUTES); inputs `TRANSACTION = hash(message)`, `POLICY_VERDICT = verdict.hash()`, `ORACLE_READING = quotesHash`, `APPROVAL = hash(decision, by, at)`; output `execution/1 = hash{status, cluster, lamports, signature, signerPublicKey, confirmationStatus, error, recentBlockhash}`; nonce `exec:<operationId>`; L0_SIGNED; emitted on terminal state only (also by the reconciler).

Field-by-field against the mandate's `ExecutionReceipt` (§15):

| §15 field | Today | Status |
|---|---|---|
| receiptId / version | `receipt_hash` / `ir/1` | exists |
| intentHash / intentId | `refs.proposalId` (UUID) + `STRATEGY` parent hash — **no `H_I` of a canonical intent** | weak |
| actor / source | `ownerId`, `agentId="execution-agent"`; `source` absent | partial |
| policyHash / policyVersion / policyDecision | only inside the hashed `POLICY_VERDICT` input | indirect |
| simulationHash | via the `SIMULATION` parent receipt (proposal-time simulation; execution-time re-simulation not persisted) | partial |
| approvalEvidence | input `APPROVAL` hash | exists |
| attestationHash / validatorResult | **absent** (attestation only in audit `EXECUTION_VALIDATED.payload.attestationSignature`, payload not stored) | **missing** |
| transactionMessageHash | input `TRANSACTION` | exists |
| signerIdentity | output `signerPublicKey` | exists |
| chain / network | `params.cluster` (chain implicit) | exists |
| signature | output `signature` | exists |
| submittedAt / confirmedAt / reconciledAt | only in `doc.execution`, not in the receipt | **missing** |
| expected / actual outcome | output `status`, `confirmationStatus`, `error`; expected = parent simulation | partial |
| finalStatus | output `status` | exists |
| receiptHash | `receipt_hash` | exists |
| verify(receipt) without UI | hash-based verify + public key | exists (body-based verify missing) |

## 13. Observability

- **Metrics** (`observability/CryptobotMetrics.java:86-127`; 13 names pinned by `PrometheusMeterNamesTest`): `market_analysis_total{result,asset}`, `risk_analysis_total{result}`, `strategies_total{result,asset}`, `approvals_total{result}`, `trade_requested_total{result,asset}`, `trade_submitted_total{asset}`, `trade_confirmed_total{result,asset}`, `trade_failed_total{stage,asset}`, `trade_reconciled_total{result}`, `reconciliation_mismatch_total`, `duplicate_trade_suppressed_total`, `outbox_pending`, `outbox_failed`, `dlq_size`, `cryptobot_reconciliation_total{outcome}`, `cryptobot_dead_letter_total{reason}`, `cryptobot_dead_letter_open`, `policy_verdicts_total{decision,escalation}`, `policy_predicate_failed_total{predicate}`, `solana_rpc_errors_total{method,cluster,kind}`, `solana_confirmation_latency_seconds{result,cluster}`, `intelligence_receipts_total{result}`, `deterministic_inference_total`, `deterministic_mismatch_total`, `validator_attestations_total{result}`, `receipt_anchors_total{result}`, `anchored_receipts_total`, `anchor_pending`, `anchor_finality_latency_seconds`. Common tags `environment, service, version, commit`. `asset` bounded to an allow-list + `other`.
- **Consumers (must not be renamed — §34):** 27 series used by `life-engine-cryptobot-demo.json` (51 panels) and `-negocio.json` (31 panels), 3 alerts (`CryptoBotDlqNotEmpty`, `CryptoBotReconciliationFailing`, `CryptoBotMainnetAttempt`), 26 rule tests, Control Tower (ticket-423); label values filtered literally (`result ∈ {approved, blocked_by_policy, …}`, `stage ∈ {preflight, sign, broadcast, onchain, rpc, other}`, `decision ∈ {allow, deny, escalate}`, `outcome`, `reason`); 6 literal `uri` values (`/api/cryptobot/proposals/{proposalId}/execute`, `…/approve`, `…/wallets/{walletId}/proposals`, `…/dead-letters/…`, `…/receipts/{hash}/verify`, `…/anchors`). Full cross table in `audit-evidence/…C… §4.3`.
- **Orphans (code, no panel):** `oracle_execution_refused_total`, `artifact_reuse_total{external}`, `provenance_depth`, `cryptobot_quotes_*`, `cryptobot_oracle_*`. **Missing:** own counters in signer/validator (PR #30, ticket-582), a "validated" counter independent of `validator_attestations_total{issued}`, `tenant`/`intentId` dimensions; uat-compose does not scrape signer/validator (no `life-engine.scrape` label). `CryptoBotMainnetAttempt` counts *any* 409 on execute (timelock, state, cooldown) — PR #30 adds `execution_refused_total{reason}`.
- **Correlation:** MDC `requestId, correlationId, tenantId, proposalId, operationId, runtimeRunId, traceId, spanId` (`LogContext.java:38-63`); `LogFields {event, status, operationId, stage, proposalId, errorCode}`; JSON logs in all three modules (ticket-573). `executionId`, `signature`, `receiptId` are message fields, not MDC. OTLP export off by default (`application.yml:354-365`).
- **Domain events** (audit `event_type`, 24 values consumed by the UI timeline `live-model.ts`): `PROPOSAL_CREATED, SIMULATED, POLICY_EVALUATED, AWAITING_APPROVAL, BLOCKED_BY_POLICY, APPROVED, REJECTED, EXPIRED, CANCELLED, EXECUTION_STARTED, EXECUTION_VALIDATED, EXECUTION_SIGNED, EXECUTION_SUBMITTED, EXECUTION_BROADCAST_UNCERTAIN, EXECUTION_RETRIED, EXECUTION_CONFIRMATION_PENDING, EXECUTION_DUPLICATE_SUPPRESSED (declared, unused), EXECUTED, EXECUTION_FAILED, RECONCILED, RECONCILIATION_AMBIGUOUS, RECONCILIATION_RETRIES_EXHAUSTED, DEAD_LETTER_RESOLVED, DEAD_LETTER_REQUEUED, WALLET_REGISTERED`. The mandate's vocabulary (§27, `IntentCreated … ReceiptIssued`) maps onto these; `ReceiptIssued` has no emitter.
- **SSE:** NOT FOUND in this service (`grep -rn "text/event-stream\|ServerSentEvent" src/main/java` → 0); the UI's `runtimeSseUrl` is the Runtime's stream.

## 14. Solana interaction

- **Client:** hand-written JSON-RPC over `WebClient` (`adapters/solana/SolanaRpcClient.java`), no `solanaj`/`sol4k`/web3 dependency; timeout 8 s; methods `getBalance, getTokenAccountsByOwner (Token + Token-2022, read), getSignaturesForAddress, getBlockHeight, getLatestBlockhash (finalized), simulateTransaction (confirmed, sigVerify=false ⇒ replaceRecentBlockhash=true, :185-204), sendTransaction (skipPreflight=false, preflightCommitment=confirmed, :214-220), getSignatureStatuses (searchTransactionHistory=true), getTransaction (finalized), requestAirdrop`. Default endpoints `api.devnet.solana.com` / `api.mainnet-beta.solana.com` (`SolanaRpcProperties.java:81-82`) — **one provider, no quorum**.
- **Transaction builder:** own legacy encoder (`adapters/solana/tx/{LegacyTransaction, CompactU16, SystemProgram, MemoProgram}`), copied verbatim into the signer ("copy-not-reuse", `signer/README.md:175-176`). **The only economic action is `SystemProgram.transfer(wallet → rebalance-vault, lamports)` for a SOL sell leg** (`SimulationService.java:49-53,70-81`). NOT FOUND: swap (`grep -rniE "jup.ag/swap|swapTransaction"` → 0; Jupiter is price-only), SPL/Token-2022 transfer builder, versioned transactions/ALT, ComputeBudget/priority fees, durable nonce (`AdvanceNonce` → 0).
- **Confirmation:** inline poll 20 × 1.5 s of `getSignatureStatuses`, then the reconciler; `lastValidBlockHeight` persisted for expiry detection.
- **Memo/anchor:** ticket-394 works on devnet (§12).
- **`programs/intent-authority` (ticket-437):** PDAs `policy(agent, version)`, `nonce(agent, nonce)`, `receipt(intent_hash)`; `Execute` checks agent signature, registered/non-revoked policy, `policy_hash`, `Clock.slot ≤ valid_until_slot`, unused nonce/receipt, writes the receipt atomically; **moves no funds**. 29 tests in CI. **Not deployed** (needs the Solana CLI — vault `Products/CryptoBot-OnChain-Authority-2026-09-16.md:67`), **not wired** (no `cryptobot.authority.*` property; no caller in `src/main`).
- **Mainnet:** §6. No genesis-hash binding anywhere.
- **Devnet evidence (queried 2026-09-21 ~08:40Z via `getSignaturesForAddress`/`getTransaction`, vault `Products/CryptoBot-Hackathon-Pitch-2026-09-20.md §5.0`):** wallet `G4bCRqj3…4exS` — **12 confirmed wallet→vault transfers, 0 failed**, 1 Memo anchor (`ngrN7DP…`, n=25, slot 501505553), 3 airdrops. Named signatures: happy path `4qeaKhgU…` slot 501489009; chaos recovery `2mU1LTY3…` slot 501489762; UAT smokes `4LEdVuaN…`, `5Pz8Vu4f…`, `65VQX2Wr…`, `4SZXErpV…`, `B1jtgHuE…`, `5ER9DKZa…`, `2jxip3B5…`.
- **Solana primitives (§21)** — classification with sources in `audit-evidence/…B… §7`: **USE NOW** `simulateTransaction` (already), Memo for on-chain correlation of the transfer (`intentId`; requires the signer to accept exactly 2 instructions: memo without accounts + transfer), ComputeBudget `SetComputeUnitLimit/Price` with bounded values (demo reliability on congested devnet; same signer whitelist change). **USE LATER** SPL/USDC `transferChecked` with mint allow-lists (phase 7/9 — supplier payment, escrow), Squads v4 spending limits (on-chain enforcement a compromised signer cannot bypass), durable nonce (Anza warns of possible deprecation; the idempotent retry already covers the case), `intent-authority` deployment, x402 / MPP / Solana Pay as **intent sources**, Token-2022 transfer hooks (only for mints we control). **NOT APPLICABLE** Confidential Balances (hides amounts; receipts need them), versioned tx/ALT until swaps exist.

## 15. Tests

Run today on `2b69b37` (logs in the lead's scratchpad; CI run `35576296231` success):

| Module | Command | Result |
|---|---|---|
| service | `./mvnw -q test` | **Tests run: 517, Failures: 0, Errors: 0, Skipped: 1** (`RiskEngineGoldenTest.writeGolden`, gated by `-Drisk.golden.write`) |
| signer | `cd signer && mvn -q test` | **33/33** |
| validator | `cd validator && mvn -q test` | **28/28** |
| program | CI `cargo test --locked` | 29 (13 unit + 14 bank + 2 vectors) |

Inventory (`audit-evidence/…A… §12`): pure domain units (policy 21, intent 34, canonicaliser 7, risk 11, receipts/Merkle/anchor 10, state machine 6, oracle 13, tx encoder 10, program client 14); application units with in-memory repositories and mocked RPC/signer/validator (`PolicyEngineTest` 28, `ExecutionServiceReliabilityTest` 11, `ExecutionRecoveryTest` 7, `ExecutionServiceMainnetGateTest` 5, `ExecutionServiceValidatorTest` 4, `OutboxPublisherTest` 4, `ReceiptServiceTest` 5, `AnchorServiceTest` 8, `LineageServiceTest` 9 …); `@SpringBootTest` with stub repositories, **no Postgres** (`ControlPlaneFlowTest` 7 incl. `fullDemoFlowAsPaperTradeWithCompleteAuditTrail`, `oversizedTradeIsBlockedByPolicyAndRecorded`, `adversarialPriceIsBlockedWithAVerifiableDecisionReceipt`; `DeadLettersAndChaosApiTest` 4; `PrometheusMeterNamesTest` 3 …); **invariants I1–I7** (ticket-440, `benchmark/InvariantsTest.java:63-314`: limit ⇒ ¬Execute · consumed nonce ⇒ ¬Execute · unauthorised agent ⇒ ¬Execute · Execute ⇒ active policyHash · Execute ⇒ allowed asset · Unknown ⇒ Deny · same (I,S,R) ⇒ same verdict in engine, validator and second run) + `AdversarialBenchmarkTest` (10 000 intents, 0 executed violations) + `ChaosTest` 14 — **all over the `AuthorityLayer` test harness, not over `ExecutionService`/HTTP**; Postgres ITs `ReceiptR2dbcStoreIT`, `AnchorR2dbcStoreIT`, `LineageR2dbcStoreIT` gated by `CRYPTOBOT_IT_PG_HOST` (never in CI); **Testcontainers NOT FOUND in `pom.xml`**; E2E `E2EDevnetIT` (3: real execution, mainnet refused, injected failure recovered without double execution) under `-Pe2e-devnet` only; `scripts/demo/tests/test-run.sh` (offline) in CI.

Gaps against §29: `ActionProposalR2dbcStore.commit` (optimistic lock + unique `operation_id` + atomic tx), `OutboxEventR2dbcStore` (`SKIP LOCKED`) and `DeadLetterR2dbcStore` have **no test against a real database** (`grep -rl ActionProposalR2dbcStore src/test/java` → 0); "DENIED never SIGNED" / "unvalidated never SUBMITTED" exist for the harness and as unit cases (`validatorRefusalFailsClosedBeforeSigning`, `validatorSilenceIsDeny`) but not as property tests over the real `ExecutionService`; "tampered receipt fails" is not tested through the API (`grep -rn tamper src/test` → 0); "stale simulation is re-simulated" is implicit; JVM E2E with real signer+validator is PR #27 (blocked).

## 16. Jira work

Epics: **ticket-583** Trusted Agent Execution (this mandate; created 2026-09-21 05:58; label `trusted-execution`) with **ticket-584** (phase 0, this document). **ticket-323** CryptoBot hackathon (En curso; HK-1..9 delivered, videos + submission pending; freeze 08/10, submit ≤ 10/10). **ticket-390** Decision Receipts (Backlog; its children built the core: ticket-403, 435, 436, 437, 438, 440, 391, 392, 393, 394). **ticket-523** Engine v1 (E1/E2 in Revisar, E5 ticket-528 policy governance, E7 ticket-530 event backbone). Searched summaries for `Trusted|intent|execution engine|receipt|signer|validator|policy|escrow|idempot` (41 hits): no other "Trusted"; `escrow`, `ExecutionReceipt`, `Chain Adapter`, `ports` appear nowhere.

| Key | Status | Relationship to the phases | Action |
|---|---|---|---|
| ticket-457 | Backlog | "unite the canonical intent with the Policy Engine: one canonicaliser, facts from the intent not the adapter" | **adopt as the phase 2 story** |
| ticket-466 | Backlog | second validator for `REQUIRE_SECOND_AGENT`, human-signature path | **adopt in phase 4** (separation of duties) |
| ticket-500 | Revisar, PR #27 | JVM E2E with real signer+validator + `intent_hash`/`execution_signature` columns | **adopt in phase 6**; unblock by renumbering `V10 → V11` and rebasing |
| ticket-493 | Revisar | mainnet fail-closed — **merged (PR #20) and deployed in UAT** | close with evidence |
| ticket-501 | Revisar | DLQ resolve/requeue + runbook — merged in PR #23 | close with evidence (the "rehearsed once in UAT" AC becomes failure demo 9 in phase 6) |
| ticket-439 | Backlog, `post-v1` | multi-source oracle — **code merged inside PR #28** (`PolicyEngine.java:48,83`) | close with evidence; the `MarketDataPort` story of phase 3 continues it with ticket-383 |
| ticket-455 | Backlog | "authority layer" Grafana panel — already in the demo dashboard (ticket-573) | close as duplicate |
| ticket-437 | Finalizada | intent-authority program (not deployed) | no phase story; USE LATER |
| ticket-450 / ticket-464 / ticket-530 / ticket-528 | Backlog | canonicalisation, reliability lib, event backbone, agent governance as Platform/Engine capabilities | phase 9 references them; the core exports contracts they consume; **not fused** |
| ticket-582 | En curso, PR #30 | fine-grained metrics; touches `ExecutionService`, `PolicyEngine`, `ReconciliationService`, `SignerController`, `ValidatorController` | merge before phase 1 package moves |
| ticket-326 | Backlog, `post-v1` | runtime PR #33 `crypto.portfolio-advisor.v1` | outside the core; decide before 03/10 |
| ticket-253 | Backlog | "Runtime V3 — Execution Engine durable" | **name collision**: Runtime's "Execution Engine" = durable runs; ours = economic execution. Keep "Trusted Agent Execution" as the name in Jira/docs. |

Full per-issue table with what each delivered: `audit-evidence/…C… §7`.

**Jira plan created with this audit (2026-09-21, all under ticket-583, label `trusted-execution`):**

| Phase | Stories |
|---|---|
| 0 | ticket-584 (this document) |
| 0.5 | ticket-585 pin `VALIDATOR_POLICY_HASH` in UAT + smoke asserts it · ticket-586 wallet key as file secret in uat-compose · ticket-587 devnet smoke evidence + k8s smoke for cryptobot · ticket-594 repo hygiene |
| 1 | ticket-595 packages `core/solana/trading` + ArchUnit · ticket-596 chain ports |
| 2 | **ticket-457** (adopted) `ExecutionIntent v1` wired to policy · ticket-597 additive API (`/intents`, `/executions`, body-based `/receipts/verify`) |
| 3 | ticket-598 isolate the trading vertical, `MarketDataPort` |
| 4 | ticket-599 destination bound approve→execute · ticket-600 validator decodes bytes / own clock / attestation v2 · ticket-601 persist attestation + `messageHash` · **ticket-466** (adopted) separation of duties · ticket-602 signer velocity, genesis hash, anchor rate limit, memo/ComputeBudget spike |
| 5 | ticket-603 `ExecutionReceipt v2`, `POLICY_DECISION` kind, keyring, `RECEIPT_ISSUED` |
| 6 | **ticket-500** (adopted; PR #27 needs `V10→V11`) JVM E2E · ticket-604 Testcontainers + property tests · ticket-605 failure taxonomy, jitter, chaos signer-down, failure demos 3/4/5/7/10 |
| 7 | ticket-606 strategy registry, trading risk → constraints, swap/SPL design doc |
| 8 | ticket-607 hackathon story of the core (links ticket-323) |
| 9 | ticket-608 ADR-ENG with E5/E7, Maven split, three intent sources by contract tests, mainnet readiness gate |

Closed with evidence on 2026-09-21: ticket-493, ticket-501, ticket-439 (code in `main` and UAT), ticket-455 (duplicate of ticket-573).

## 17. Gaps

Consolidated from the three reports; ordered by the mandate's priority (demo reliability › security › correctness › reuse › observability). Sev H/M/L is the impact given devnet, a 2 SOL cap and an operator-owned vault.

| # | Gap | Evidence | Sev | Phase |
|---|---|---|---|---|
| G1 | ~~Destination not bound approval → execution (config read at execute time; no compare)~~ **CLOSED 2026-09-22** | `SimulationService.java:75`; grep `destination()` → 0 (before); `SIGNER_ALLOWED_DESTINATIONS` empty by default — now `ExecutionService.requireDestinationBound` (`run`, before the validator, also on `retry`); PR [#33](https://github.com/sebdev89/life-engine-cryptobot-service/pull/33) (ticket-599); test `ExecutionServiceDestinationBindingTest` (5, incl. the drift property over 15 random pairs) | **H** | 4 |
| G2 | Canonical intent not wired; `(I)` fabricated from plan + config (`POLICY_BOUND` tautological, slippage from config); no `source/tenant/destination/maxFee/metadataHash/schemaVersion` persisted | `PolicyEngine.java:447-485`; `ProposalsController.java:131-133`; ticket-457 | **H** | 2 |
| G3 | Validator does not decode bytes, uses caller-frozen `current_slot`/`nonce_unused`, no own clock/state | `ValidationService.java:202-236`; `ValidatorClient.java:299-301` | **H** | 4 |
| G4 | Attestation payload and `messageHash` not persisted; `EXECUTION` receipt lacks attestation/validator/policyHash/timestamps | `ExecutionService.java:328-331`; `Receipts.java:378-388` | **H** | 4–5 |
| G5 | Wallet private key as env var in uat-compose; SOPS for these values absent from `deploy` | `docker-compose.uat.yml:1063` | **H** | 0.5 (INFRA) |
| G6 | `VALIDATOR_POLICY_HASH` unpinned in uat-compose and k8s-uat | compose `:1020`; `cryptobot-validator.env:19` | **H** (config) | 0.5 (INFRA) |
| G7 | Critical persistence untested against Postgres (optimistic lock, unique `operation_id`, `SKIP LOCKED`) | no Testcontainers; `grep -rl ActionProposalR2dbcStore src/test` → 0 | M | 6 |
| G8 | Receipt signing key ephemeral by default; no keyring on rotation | `ReceiptKeyConfig.java:24-37`; `ReceiptService.java:245` | M | 5 |
| G9 | No chain ports; `application` imports `SolanaRpcClient` ×7; `Wallet` imports `SolanaCluster`; core and vertical in one aggregate | §2 | M | 1 |
| G10 | No failure taxonomy; no jitter; signer outage = terminal `FAILED` | §11 | M | 6 |
| G11 | Separation of duties absent; `ESCALATE` = timelock only | `CryptobotSecurityConfig.java:77-78`; `PolicyEngine.java:56` | M | 4 (ticket-466) |
| G12 | Cluster label declarative — no genesis-hash binding; single RPC provider | §8, §14 | M | 4 |
| G13 | PR #27 (ticket-500) blocked by `V10` collision | `git ls-tree origin/ticket-500-…` | M | 0.5 |
| G14 | k8s-uat does not smoke CryptoBot; devnet smoke PASS/SKIP + signature not written to `ops.d/evidence` | `ops.d/lib/services.sh:216`; `smoke-uat-k8s.sh` → 0 | M | 0.5 (INFRA) |
| G15 | Failure demos 4 (slippage), 5 (mint), 7 (signer down), 10 (tampered receipt) not scriptable; no body-based `POST /receipts/verify` | §27 | M | 6 |
| G16 | Golden path "Buy 50 USDC of SOL" not executable (no swap/SPL) | `SimulationService.java:49-53` | M (product) | 7+ / narrative now |
| G17 | Proposal creation not idempotent (no nonce/key on `POST …/proposals`); `operationId` opaque, not bound to the economic action | `ProposalService.java:161` | M | 2 |
| G18 | Policy Decision Receipt stored as `RISK_DECISION` (no `POLICY_DECISION` kind) | `V6:42-44`; `Receipts.java:252` | L | 5 |
| G19 | `ASSET_CONCENTRATION`/`DAILY_LIMIT` (portfolio) inside execution policy | `DeterministicPolicyEngine.java:112-114` | L | 3 |
| G20 | Signer: no velocity cap, no own metrics (PR #30), `sign-anchor` without attestation/rate limit | §8 | L | 4 |
| G21 | Audit append-only by convention only (no trigger/REVOKE, no hash chain) | `V4:82-94` | L | 5 |
| G22 | Runtime ↔ CryptoBot coupling outside the core (`ext/cryptomarketreview` tools; `portfolio-advisor` missing in main) | `…C… §5.2` | L | out of scope (ticket-326) |
| G23 | Jira drift (493/501/439 open with code shipped; 455 duplicate); repo hygiene (stale branches, 62-file stale worktree, dirty main checkout, duplicate `docker-compose.hackathon.yml`) | §4, §16 | L | 0.5 |
| G24 | No SBOM/dependency scan in CI | `ci.yml` grep → 0 | L | 6 |

## 18. Threat model

Full table (asset, path, existing mitigation with `file:line`, what is missing, severity, proposed change, proving test) in `audit-evidence/2026-09-21-B-trust-boundaries.md §6`. Summary of the mandate's list (§24):

| Threat | Existing mitigation | Missing | Sev | Proving test (to add) |
|---|---|---|---|---|
| Malicious user with OPERATOR JWT | owner scoping; policy limits, cooldown; signer cap + allow-list | separation of duties; signer velocity | M | `selfApprovalRefusedForEscalatedTier`; `SigningPolicyTest.refusesOverDailyCap` |
| Prompt injection / compromised LLM or strategy | LLM output is text + provenance only; cannot create/execute (`AdvisorService.java:36-44`); deterministic policy; human approval; validator; signer | — | L | exists: `validatorRefusalFailsClosedBeforeSigning` |
| Compromised API credential | limits, timelock on ESCALATE, cancel | ALLOW tier has 0 s timelock (configurable `CRYPTOBOT_TIMELOCK_AUTONOMOUS`) | M | exists: `ProposalServiceTimelockTest` |
| Replay / duplicate execution | `operationId` + version guard; attestation bound to proposal + bytes; signature persisted before broadcast | — | L | exists: `ExecutionServiceReliabilityTest`, `ExecutionRecoveryTest`, demo replay |
| Signer compromise | only process with the key; no RPC/DB; non-root | key via env var in uat-compose; no HSM/TEE; no velocity | H | smoke: `SIGNER_KEYPAIR_JSON` must be empty in the container |
| Validator compromise | signer still bounds by bytes | validator adds no byte-level limit | M | `ValidationServiceTest.refusesWhenBytesDoNotMatchFacts` |
| DB tampering | signed receipts + verify; `H_R` re-checked at execution | attestation/`messageHash` not persisted; audit not append-only at DB level | M | `tamperedExecutionReceiptFails` via API; attestation re-verifiable from DB |
| RPC lying / unavailable | `searchTransactionHistory`, two reads for expiry, never blind re-send; chaos tests | single provider | M | chaos exists |
| Malicious token / program | signer: System transfer only | n/a until SPL | L | exists |
| Slippage / price manipulation | quorum, deviation, breaker, drift, fresh read at execution | slippage is config, not a DEX quote | L (no swap) | exists |
| MEV | n/a (transfer to own vault) | — | L | — |
| Dependency compromise | JDK crypto, few deps | no SBOM/scan | M | CI job |
| Secret / log leakage | no secret logging; `include-message: never` | signer key in env (UAT) | M | smoke |
| Wrong policy deployment | `H_R` in verdict, binding at execution, validator pin | UAT unpinned | M | smoke asserts `pinned=true` |
| Approval bypass | `executionPreconditions` (APPROVED + record) | approval not inside the attestation | L | `attestsApprovalHash` |
| Stale simulation | re-simulation on exact bytes at execution | result not persisted; `replaceRecentBlockhash=true` | L | `persistsExecutionSimulation` |
| TOCTOU validate → sign → submit | hash chain validator→signer→service→RPC, `verifySigned` | `messageHash` not persisted | L | exists: `attestationTravelsToTheSigner` |
| Mainnet enabled by accident / mislabelled bytes | 3 processes × flags, hard-coded `execution-cluster`, smoke | no genesis-hash binding | M | `refusesRpcWhoseGenesisIsNotDevnet` |

## 19. Target architecture

Decision recorded in the ADR. The target is the mandate's pipeline, realised by **separating what is already there**, not by adding services:

```
                       ┌──────────────────────────── cryptobot-trading (vertical, probabilistic allowed) ────────────────────────────┐
  Runtime LLM ──▶ advisor (text only) ─▶ portfolio ─▶ signals ─▶ strategy (REBALANCE) ─▶ trading risk ─▶ INTENT PRODUCER ─▶ ExecutionIntent v1
                                                                  market-data (MarketDataPort: Jupiter/Pyth/CoinGecko/Coinbase = untrusted input)
                       └──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
                                                                                     │  POST /intents  (alias: POST /wallets/{id}/proposals)
                       ┌──────────────────────────── trusted-execution-core (deterministic; knows no strategy) ────────────────────────┐
  Intent Gateway (schema, idempotency by intentHash, tenant/actor from JWT)
     ▶ Simulation (ChainSimulationPort)  ▶ Execution Policy (R_v, H_R: network, mainnet, limits, allow-lists, slippage, simulation ok, price integrity)
     ▶ Decision ALLOW | DENY | ESCALATE(=APPROVAL_REQUIRED)  ▶ Approval (human; separation of duties by tier)  ▶ Timelock
     ▶ Independent Validator (own process; decodes bytes; own clock; attests {intent, policy, bytes, cluster, approval})
     ▶ Signer (own process; one shape; cap; allow-list; attestation required)  ▶ Submission (ChainExecutionPort)  ▶ Confirmation (ChainObservationPort)
     ▶ Reconciliation (matched/corrected/retried/dead_lettered/skipped)  ▶ Retry (bounded, classified)  ▶ DLQ (resolve/requeue)
     ▶ ExecutionReceipt (signed, content-addressed, anchored)  ▶ Audit (append-only) ▶ Metrics/Logs (existing names)
                       └──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
                                                                                     │ ports only
                       ┌──────────────────────────── solana-execution-adapter ─────────────────────────────────────────────────────────┐
  SolanaRpcClient (rpc) · LegacyTransaction/SystemProgram/MemoProgram (transaction-builder) · simulateTransaction · sendTransaction ·
  getSignatureStatuses/getTransaction (chain-observer) · Token/Token-2022 read (AssetPort) · [later: SPL transfer, ComputeBudget, swap]
                       └──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
  Trust processes unchanged: validator/ and signer/ stay separate JVMs with their own keys and tokens.
```

Trust boundaries of the *actual* implementation (where keys live, where policy lives, DB truth, chain truth) are drawn in [`trusted-agent-execution-trust-boundaries.md`](trusted-agent-execution-trust-boundaries.md).

Layer classification (ADR-PLAT-017 requires it before phase 1): `trusted-execution-core` and `solana-execution-adapter` are **Engine-layer capabilities incubated inside the CryptoBot repository**; `cryptobot-trading` is the **vertical**. Extraction to Engine (E5 governance, E7 backbone, ledger) is phase 9 and needs an ADR-ENG; until then nothing is moved out of the repo.

## 20. Current → target mapping

Per package/class (`audit-evidence/…A… §14a`), condensed:

| Today | Target module | Action |
|---|---|---|
| `domain/transactions/*` (ActionProposal, ProposalStatus, ProposalTransition, ExecutionRecord, ApprovalRecord, AuditEvent, PreparedTransaction, SimulationOutcome) | core/{intent, approval, submission, audit} | keep; `ActionProposal` becomes the core aggregate (`Execution`) without renaming persisted states; Solana-specific construction of `PreparedTransaction` moves behind the adapter, the core keeps `{messageHash, bytes}` |
| `domain/intent/*` (TradingIntent, IntentSchema, JsonCanonicalizer, IntentHash, AssetId) | core/intent | generalise `TradingIntent` → `ExecutionIntent` (§4 fields); one canonicaliser (absorbs `domain/policy/CanonicalJson`) |
| `domain/policy/*` + `PolicyEngine` rules `MAX_*`, `ASSET_ALLOWLIST`, `COOLDOWN`, `PRICE_*`, `EXECUTION_*`, `SIGNER_*`, `VALIDATOR_AVAILABLE`, `TIMELOCK_ELAPSED`, `ONCHAIN_SIMULATION_PASSED` | core/policy | keep; read `(I)` from `ExecutionIntent`; `policyInput()` (plan → I,S) moves to trading/intent-producer; `REBALANCE_VAULT_CONFIGURED` → trading config |
| `domain/risk/*`, `application/controlplane/RiskEngine` | trading/trading-risk | move whole; `ASSET_CONCENTRATION`/`DAILY_LIMIT` decided: `DAILY_LIMIT` stays (velocity = execution policy), `ASSET_CONCENTRATION` moves to trading risk as a *constraint the intent producer sets* (`constraints.maxExposureBps`) |
| `domain/strategy/*`, `RebalancePlanner`, `PortfolioService`, `domain/portfolio`, `AnalysisReuse`, `AdvisorService`, `domain/advisor`, `integration/lifeengine`, `infrastructure/runtime`, `application/{MarketReview*, Monitoring*, Watchlist, PriceZones, TradeJournal, Indicators, MarketObservations}`, `api/*` (root), `infrastructure/{snapshot, binance, solana/SolanaPublicClient}`, `infrastructure/persistence/r2dbc` | trading/{strategy, portfolio, signals, market-data} | move; the LLM path never touches the core |
| `ProposalService` | split: `createRebalance` (plan + risk) → trading/intent-producer; `simulate`, `evaluatePolicy`, `decide`, `cancel`, `expireIfDue`, `commit` → core/{simulation, policy, approval} | split |
| `ExecutionService` | core/{validator, signing, submission, retry} | keep; RPC calls through `ChainSimulationPort`/`ChainExecutionPort`/`ChainObservationPort` |
| `SimulationService` | split: `economic()` → trading; `prepareTransfer` + on-chain → adapter/{transaction-builder, simulation} | split |
| `AuditService`, `ExecutionReceipts`, `Receipts.{execution, policyDecision, simulation}` | core/{audit, receipts} | keep; `Receipts.{strategy, riskDecision, walletSnapshot, humanIdea, marketAnalysis}` → trading |
| `application/reliability/*`, `domain/reliability/*` | core/{reconciliation, retry, dlq, idempotency} | keep as is (already generic except `assetOf` for metrics) |
| `application/receipt/{ReceiptService, ReceiptKeyConfig, DeterministicReproducer, LineageService}` | core/receipts | keep; `AnchorService`/`AnchorJob` → adapter/chain-observer behind an `AnchorPort` |
| `adapters/solana/*` | adapter/{rpc, transaction-builder, confirmation, chain-observer} | keep; `SolanaCluster` → core `Network` enum (removes `Wallet` → adapter import) |
| `adapters/solana/authority/*`, `programs/intent-authority` | adapter/program (optional) | USE LATER; not wired |
| `adapters/marketdata/*`, `application/oracle`, `domain/oracle` | trading/market-data (`MarketDataPort`) + core consumes an `OracleReading` (quotesHash) as policy input | split |
| `api/controlplane/{Proposals, Wallets.propose, Receipts, Lineage, DeadLetters, Anchors}` | core/api (`/intents`, `/executions`, `/receipts`) — **additive aliases**, `/proposals/**` kept | keep + alias |
| `ChaosController`, `PriceChaosController`, `application/chaos` | demo (outside the core; `ChaosSolanaRpcClient` becomes a decorator of the ports) | keep gated |
| `security/*`, `observability/*`, `health/*` | cross-cutting | keep |
| `signer/`, `validator/` | trust processes | keep; harden (§9, §8) |

## 21. Module boundaries

Phase 1 enforces boundaries **inside the existing Maven module** with Java packages and an ArchUnit test (no Maven split, no new service):

```
io.lifeengine.cryptobot.core.{intent, policy, simulation, approval, attestation, signing, submission, reconciliation, retry, dlq, receipts, audit, ports}
io.lifeengine.cryptobot.solana.{rpc, tx, simulation, observer, asset}          implements core.ports.*
io.lifeengine.cryptobot.trading.{marketdata, signals, strategy, portfolio, risk, intent}   depends on core (produces ExecutionIntent), never on solana.tx/signing
io.lifeengine.cryptobot.{api, security, observability, health, demo}
```

Rules (ArchUnit, phase 1 DoD): `core` depends on nothing but `core` and the JDK/Spring; `solana` depends on `core.ports` only; `trading` depends on `core.intent` + `core.ports.MarketDataPort` and never on `solana.tx`, `core.signing`, `integration.signer`; `api` depends on `core` and `trading` application services; the signer client is reachable only from `core.signing`. Maven modules (`execution-core`, `solana-adapter`, `trading`) are a phase 9 decision, once the ArchUnit rules have held for a release.

Signer and validator remain separate deployables because the property they give — the private key and the policy pin live in processes the service cannot read — is exactly what the mandate asks to preserve (§3).

## 22. API contracts

Existing endpoints (all `/api/cryptobot/**`, JWT, owner-scoped; `audit-evidence/…A… §10`) are **kept unchanged**. Consumers: UI (`control-plane-api.ts`, `receipts-api.ts`, `lineage-api.ts`, `reliability-api.ts`; uses body `{operationId}` because the `Idempotency-Key` header is not CORS-allowed), `deploy/uat/smoke-cryptobot-devnet.sh`, `scripts/demo/{run,e2e-devnet}.sh`, Grafana `uri` literals, alert `CryptoBotMainnetAttempt`, Runtime `ext/cryptomarketreview` (6 legacy read endpoints).

Additive contract (phase 2), documented in OpenAPI, versioned by `schemaVersion` in the body:

| Mandate §28 | Today | Phase 2 addition |
|---|---|---|
| `POST /intents` | `POST /wallets/{id}/proposals` (kind REBALANCE, targetWeights) | `POST /api/cryptobot/intents` accepts `ExecutionIntent v1`; idempotent by `idempotencyKey` (= `intentHash` by default); returns the same proposal resource; the old endpoint builds an `ExecutionIntent` internally |
| `POST /intents/{id}/approve` | `POST /proposals/{id}/approve` (+ `reject`, `cancel`) | alias; `reject`/`cancel` kept |
| `POST /intents/{id}/execute` | `POST /proposals/{id}/execute` | alias; same idempotency semantics (header **and** body) |
| `GET /executions/{id}` | `GET /proposals/{id}` (`doc.execution`) | alias returning the execution view (`executionId = proposalId` until an execution table exists — none is planned) |
| `GET /executions/{id}/receipt` | `GET /proposals/{id}/receipts` (list) | alias returning the `EXECUTION` receipt |
| `POST /receipts/verify` | `POST /receipts/{hash}/verify` (hash must be in DB) | **new**: verify a receipt supplied in the body (canonical, signature, parents if present, anchor proof) — closes failure demo 10 |

No generic bypass endpoint exists today and none is added. `ESCALATE` stays the persisted/metric value; `APPROVAL_REQUIRED` is documented as its alias in the contract.

## 23. State changes

**No persisted state is renamed** (§5). `ProposalStatus` (11), `ExecutionRecord.status` (4), outbox/dead-letter/anchor statuses and the 24 audit event types stay. Justified additions:

| Change | Why | Phase |
|---|---|---|
| `EXECUTION_VALIDATED` audit payload gains `attestationPayload`, `messageHash`, `validatorPublicKey` (additive JSONB) | re-verify the attestation from the DB (G4) | 4 |
| `ExecutionRecord` gains `messageHash`, `attestationHash`, `simulationHash`, `submittedAt/confirmedAt/reconciledAt` (already `submittedAt`/`confirmedAt` partially) | receipt §15 and TOCTOU evidence | 4–5 |
| Audit event `EXECUTION_REFUSED{reason}` emitted where a 409 is returned today (mainnet, timelock, state, cooldown) | metric/audit parity with PR #30 | 4 |
| Audit event `RECEIPT_ISSUED` + outbox `receipt.created` actually emitted | mandate vocabulary; constant already exists | 5 |
| `ExecutionRecord.status` `CHECK` added at DB level | sub-state integrity | 5 |
| Failure classification `{TRANSIENT, PERMANENT, POLICY, SECURITY, CHAIN, DEPENDENCY, UNKNOWN}` recorded on `EXECUTION_FAILED` and dead letters (`payload.class`) | §14 | 6 |
| `ESCALATE` → **not renamed**; `APPROVAL_REQUIRED` alias in API docs only | C3 | 2 |

Transitions added: none. `SUBMITTED → EXECUTING` (idempotent retry) already exists.

## 24. DB changes

All additive, Flyway `V11+` (PR #27 must first renumber its `V10` to `V11`; this plan takes `V12+` if #27 lands first — the numbers below are relative).

| Migration | Content | Phase |
|---|---|---|
| `V(n)` | `action_proposal`: `intent_hash VARCHAR(71)` (sha256:…) UNIQUE partial per owner, `intent_schema_version`, `source VARCHAR(32)`, `correlation_id`, `request_id`; index on `intent_hash` | 2 |
| `V(n+1)` | `intelligence_receipt`: extend `chk_intelligence_receipt_kind` with `POLICY_DECISION` (data migration: none — existing `RISK_DECISION` rows from `policy-engine` are left as they are; new ones use the new kind) | 5 |
| `V(n+2)` | `audit_event`: trigger that rejects `UPDATE`/`DELETE` (append-only at DB level); optional `prev_hash` column left for a later decision | 5 |
| `V(n+3)` | `execution_attestation(proposal_id, attempt, validator_pubkey, payload JSONB, signature BYTEA, message_hash, issued_at, expires_at)` — one row per signing attempt (today one slot per proposal in `doc.execution`) | 4 |
| `V(n+4)` | `dead_letter.failure_class`, `outbox_event.failure_class` | 6 |

Not planned: an event-sourcing rewrite; a separate `execution` table (the proposal row *is* the execution; `executionId = proposalId`); a `policy_evaluation` table (JSONB + audit + receipt already give the causal history and `H_R`).

## 25. Migration plan

No big bang; every phase ships behind the existing tests, `scripts/demo/run.sh` (4 acts) and the UAT smoke, and never renames a metric, a route, a state or an env var (§34).

| Phase | Content | Ships when | Depends on |
|---|---|---|---|
| **0** | this audit, ADR, diagram, Jira plan | now (ticket-584) | — |
| **0.5 quick wins** (config/ops, no code in the pipeline) | pin `CRYPTOBOT_POLICY_HASH` in `.env.uat` + gitops env and make the smoke assert `pinned=true` (G6) · signer key as a file secret in uat-compose (G5) · renumber PR #27 `V10→V11` and rebase (G13) · write devnet smoke PASS/SKIP + signature into `ops.d/evidence`, add cryptobot to the k8s smoke (G14) · close ticket-493/501/439/455 with evidence, prune stale branches/worktrees (G23) | before 08/10 | INFRA gates for secrets |
| **1 boundaries** | packages `core/solana/trading` + ArchUnit rules; `Network` enum in core; chain ports implemented by `SolanaRpcClient`; `ChaosSolanaRpcClient` as port decorator; zero behaviour change (same tests, same metric names, same routes) | after PR #30 merges; before 08/10 if `run.sh` and 517/33/28 stay green, otherwise 13/10 | G9 |
| **2 canonical intent** (ticket-457) | `ExecutionIntent v1` from `TradingIntent`; `POST /intents` + aliases; `(I)` built from the intent; `intent_hash` persisted; creation idempotent by intent hash; body-based `POST /receipts/verify`; `APPROVAL_REQUIRED` alias | 13/10 → | 1 |
| **3 isolate trading** | move risk/portfolio/planner/advisor/market-review to `trading`; `MarketDataPort` (continues ticket-439/ticket-383); `ASSET_CONCENTRATION` as an intent constraint; one reference strategy (REBALANCE) as intent producer | after 2 | 2 |
| **4 harden trust contract** | validator decodes bytes + own clock + `approval_hash` in attestation (G3); persist attestation + `messageHash` (G4); destination binding approve↔execute (G1); separation of duties by tier (G11, ticket-466); signer velocity cap + genesis-hash check in the service (G12, G20); ComputeBudget + memo with a 3-instruction whitelist in the signer (demo reliability; **only if the E2E stays green**) | after 3 (destination binding and policy pin can go earlier as 0.5 if isolated) | 1 |
| **5 execution receipt** | `execution/2` output with §15 fields; `POLICY_DECISION` kind; keyring in `verify`; receipt key required outside `local/test`; `RECEIPT_ISSUED`/`receipt.created` emitted; audit append-only trigger | after 4 | 4 |
| **6 E2E + invariants** | ticket-500 JVM E2E; Testcontainers ITs for `ActionProposalR2dbcStore`/outbox/DLQ (G7); property tests over `ExecutionService` (DENIED never SIGNED, unvalidated never SUBMITTED, one key ⇒ one execution, mainnet off ⇒ no execute, tampered receipt fails, expired approval cannot sign); failure taxonomy + jitter; failure demos 4/5/7/10 scripted (`run.sh` acts) | after 5 | 2, 5 |
| **7 trading reference impl** | strategy registry (DCA/TP-SL/momentum as *interfaces* only), REBALANCE through the public core API only; swap/USDC design doc (needs versioned tx + SPL in the signer — explicitly post-hackathon) | after 6 | 3 |
| **8 hackathon demo** | ticket-323 as is: `run.sh` acts 1–4, UI `/live`, Grafana demo dashboard, pitch; golden path narrated as "rebalance SOL 70 → 50 %" with the intent shown in canonical form; refusal paths 2, 3, 6, 8, 9 live | until 10/10 | 0.5 |
| **9 reusable integration** | ADR-ENG aligning with E5 (governance ≠ execution policy) and E7 (contracts, transport decided there); Maven module split; second intent source demonstrated without core changes (USDC supplier payment / escrow release / agent payment as *contract tests*, execution requires SPL in the adapter) | after 12/10 | 7 |

Rollback per phase: every phase is a PR on `main` deployed to UAT by digest; rollback = previous digest (`./ops uat rollback`, gitops digest revert). DB migrations are additive (new columns/tables/CHECK values), so a code rollback never needs a schema rollback.

## 26. Test strategy

Layered as §29, mapped onto what exists:

- **Unit:** transitions (`ProposalStatusTest` ✓), policy predicates/vectors (✓ 21), limits (✓), risk (✓), receipt hashing/canonicalisation (✓ vectors), idempotency (`ExecutionServiceReliabilityTest` ✓), failure classification (**new**, phase 6), `ExecutionIntent` schema + vectors (**extend** `IntentSchemaTest`, phase 2), ArchUnit boundaries (**new**, phase 1).
- **Property / invariants:** I1–I7 exist over `AuthorityLayer` (ticket-440). Add the same properties over `ExecutionService` with in-memory repositories and a mocked chain port (phase 6): DENIED never SIGNED; unvalidated never SUBMITTED; same `idempotencyKey` ⇒ ≤ 1 broadcast; mainnet disabled ⇒ no `sendTransaction`; tampered receipt ⇒ `valid=false` via `POST /receipts/verify`; expired approval ⇒ no signer call; stale simulation ⇒ re-simulated.
- **Integration:** Testcontainers Postgres for `ActionProposalR2dbcStore` (optimistic lock, unique `operation_id`, atomic audit+outbox), `OutboxEventR2dbcStore` (`SKIP LOCKED` with two workers), `DeadLetterR2dbcStore` (single resolution) — phase 6; JVM E2E with real signer + validator + mocked RPC (ticket-500) — phase 6; validator byte-decoding tests (phase 4); signer velocity/whitelist tests (phase 4).
- **E2E:** `E2EDevnetIT` (3) + `scripts/demo/e2e-devnet.sh` chaos modes ✓; add scripted negative paths 4, 5, 7, 10 (phase 6) and keep `scripts/demo/tests/test-run.sh` in CI.
- **CI:** `build-and-test` stays the gate; add `dependency-check`/`trivy` (G24); consider `cancel-in-progress: false` on `main` so every merged commit gets an image.

## 27. Hackathon demo path

What runs today (`scripts/demo/run.sh`, 4 acts; evidence in `out/run-<ts>/` and the vault): **Act 1** wallet → REBALANCE intent → plan → simulation → 21 rules + `R_v` + validator identity → execute before approve = 409 → approve → timelock → execute with `Idempotency-Key` → validator attests → signer signs → `sendTransaction` → SUBMITTED → EXECUTED → replay same key = same signature → outbox → `EXECUTION` receipt verified → mainnet intent = 409. **Act 2** adversarial intent → `BLOCKED_BY_POLICY` with named rules; cooldown; `PUT /demo/price` −90 % → `PRICE_DEVIATION`; all sources stale → `PRICE_STALE`; each with an L1-reproduced receipt. **Act 3** `--chaos rpc-down` → SIGNED persisted → no verdict ×3 → dead letter `ambiguous` → RPC back → `requeue` → `RETRIED` (same `operationId`, new signature) → EXECUTED → one transfer on chain. **Act 4** metrics read from `/actuator/prometheus`; Grafana demo dashboard in UAT.

Golden path §42 vs reality: the intent the system executes is **"rebalance SOL 70 % → 50 %"**, whose executable leg is one SOL transfer to the vault. "Buy 50 USDC of SOL" (a swap) is **not executable** and would require versioned transactions + Jupiter routes that the byte-level signer cannot bound before 12/10 (C4). Decision: narrate the canonical intent (phase 2 can show `ExecutionIntent{operation: SWAP, assetIn: USDC, amount: 50, assetOut: SOL}` being **refused** by policy because `allowedProtocols` is empty — safety visible), execute the rebalance leg.

The ten failure demos (§30):

| # | Demo | Today | To add |
|---|---|---|---|
| 1 | valid → CONFIRMED | ✓ act 1, UAT smoke, 12 devnet transfers | — |
| 2 | mainnet → DENIED | ✓ act 1 (409), smoke flags, 3 layers | — |
| 3 | excessive amount → DENIED | ✓ act 2 (by %); USD cap exists but not scripted | script `MAX_TRADE_USD` case (phase 6) |
| 4 | excessive slippage → DENIED | tests only (`maxSlippageBps` is config) | needs the intent to carry `maxSlippageBps` (phase 2) |
| 5 | unauthorised mint → DENIED | rule exists (`ASSET_ALLOWLIST`), not scripted | one line in `run.sh` act 2 (`targetWeights:{BONK:…}`) — safe before the freeze |
| 6 | duplicate request → one execution | ✓ act 1 replay + `duplicate_trade_suppressed_total` | — |
| 7 | signer down → retry / safe | safe ✓ (FAILED at `SIGN`, nothing broadcast); retry ✗; no chaos mode | chaos `signer-down` + classified TRANSIENT retry (phase 6) |
| 8 | RPC lost after submit → reconciliation finds tx | ✓ `--chaos uncertain|confirm-timeout` | — |
| 9 | permanent failure → DLQ | ✓ act 3 (`ambiguous`), `retries_exhausted` after 2 | — |
| 10 | tampered receipt → verify fails | tests only | body-based `POST /receipts/verify` + act 4 tamper step (phase 2/6) |

## 28. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Phase 1 package moves collide with PR #30 (ticket-582) and other open work on `ExecutionService`/`PolicyEngine` | high | rebase churn | merge #30 first; one lease on `cryptobot-service` at a time; phase 1 is a single PR with zero semantic diff |
| Refactor breaks the demo before the freeze | medium | hackathon | `run.sh` 4 acts + `test-run.sh` + UAT smoke as merge gate; anything not green by 08/10 waits for 13/10 |
| Renaming a metric/route/state by accident | medium | dashboards, UI, alerts | `PrometheusMeterNamesTest` extended to the 27 series; route contract test; ArchUnit does not touch runtime names |
| Validator hardening (decoding bytes) changes attestation payload → signer incompatibility | medium | nothing signs | version the attestation (`schema_version: "2"`), signer accepts 1 and 2 during rollout, deploy validator first |
| Signer whitelist widened for memo/ComputeBudget opens a new shape | low | funds (devnet) | exact instruction-count and program-id checks, bounded compute price, tests per shape; skip if the E2E shows no benefit |
| Secrets handling changes in UAT (key as file, policy pin) done by hand | medium | UAT down | INFRA gate, one change per deploy, smoke after each |
| Jira/vault drift keeps growing | high | wrong decisions | close 493/501/439/455 now; vault Endgame metric names corrected in phase 5 docs |
| Engine E5/E7 later demand a different policy/event contract | medium | rework | phase 9 ADR-ENG before extraction; the core exports contracts, does not import Engine types |

## 29. Non-goals

Not in this plan: a token; a new Solana program before `intent-authority` has a wiring decision; multi-chain abstractions beyond the four ports; microservices for the core (same JVM); Kafka/RabbitMQ/NATS (transport decided in E7 after 12/10 — the core only defines the event contract); mainnet (stays `DENY` until an independent readiness gate exists — no issue yet, proposed as the last story of phase 9); MPC/HSM/TEE custody; swaps, SPL/USDC transfers, escrow programs before 12/10; renaming `ESCALATE`, `/proposals/**`, any metric or audit event; an event-sourcing rewrite; a separate execution table; running the core inside Runtime; calling the design a "protocol" or "standard" (vault claims policy).

## 30. Recommended execution order

1. **Now (lead):** merge PR #30; ticket-493/501/439/455 closed with evidence (done 2026-09-21); phase stories ticket-585…ticket-608 created under ticket-583 and ticket-457/466/500 adopted (done — §16); prune stale branches/worktrees (ticket-594).
2. **Before 08/10 (INFRA, gated):** ticket-585 (policy pin + smoke assert) → ticket-586 (signer key as file secret) → ticket-587 (smoke evidence + k8s smoke); VERTICALS: renumber and rebase PR #27 (ticket-500).
3. **Before 08/10 (VERTICALS, only if zero behaviour change and `run.sh` green):** ticket-595 → ticket-596 (phase 1 boundaries); optionally ticket-599 (destination binding) if isolated; failure demo 5 (mint) as one script line (ticket-607).
4. **13/10 → :** ticket-457 → ticket-597 → ticket-598 → ticket-599/600/601/466/602 → ticket-603 → ticket-500/604/605 → ticket-606 → ticket-608, one PR per story, each deployed to UAT by digest and smoked before the next.
5. **Continuous:** every story updates this document's §17 gap table (mark closed with the PR and the test that proves it).

### `READY_TO_IMPLEMENT=true`

Conditions (not blockers for starting phase 1; blockers for calling any phase DONE):

- **B1** PR #30 (ticket-582) merged before phase 1 touches `ExecutionService`/`PolicyEngine` (lease conflict, not a design blocker).
- **B2** Hackathon freeze: anything that changes demo behaviour waits until 13/10; phases 1 and 0.5 are exempt because they do not.
- **B3** INFRA gates for secrets/config (policy pin, key as file) — operator action, commands ready in the Jira stories.
- **B4** Open question P-1 (which authorities the S2S token `sub=service:cryptobot` derives in Auth) must be answered in phase 4 before separation of duties is declared done; until then the LLM path is already unable to execute by code (§6).
- **B5** PR #27 `V10` collision resolved before any new migration is numbered.
