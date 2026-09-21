# ADR — CryptoBot trading strategy is separated from a generic Trusted Agent Execution Core

- **Status:** proposed (2026-09-21) — accepted when Sebastián signs off KAN-584.
- **Issue:** KAN-584 · epic KAN-583 · related: KAN-390 (Decision Receipts), KAN-323 (hackathon), KAN-523 (Engine v1: E5 KAN-528, E7 KAN-530), ADR-PLAT-017 (layer boundary), ADR-ENG-001/002 (Runtime canonical model, agent contracts).
- **Evidence:** [`TRUSTED-AGENT-EXECUTION-AUDIT.md`](TRUSTED-AGENT-EXECUTION-AUDIT.md) and the three reports in [`audit-evidence/`](audit-evidence/).

## Context

CryptoBot (`life-engine-cryptobot-service` at `2b69b37`) was built for the Colosseum hackathon as "an AI control plane for Solana wallets". The audit shows it is, in code, two things fused into one aggregate and one package tree:

1. A **deterministic execution pipeline**: canonical policy `(I,S,R_v) → ALLOW|DENY|ESCALATE` pinned by hash, simulation on the exact bytes, human approval with timelock, an independent validator process that attests the transaction hash, a signer process that signs one transaction shape under a cap and an allow-list, submission, confirmation, version-guarded state machine, idempotent retry, reconciliation with named outcomes, dead letters, signed content-addressed receipts anchored on devnet. Verified by 517 + 33 + 28 tests and twelve confirmed devnet transfers (audit §1, §14, §15).
2. A **trading vertical**: portfolio valuation, multi-source price oracle, rebalance planner, portfolio risk engine, LLM advisor through Runtime, ARS quotes, a legacy market-review workflow (audit §2, §21).

They share `ActionProposal` (`plan`, `riskBefore/After` next to `policy`, `simulation`, `transaction`, `approval`, `execution`), share `ProposalService`, and the policy input `(I)` is fabricated from the rebalance plan and from the engine's own configuration rather than from a caller-supplied intent (`PolicyEngine.java:447-485`). The canonical intent from KAN-435 exists but is not wired (audit §7). `application` imports the Solana RPC client directly in seven places; there are no chain ports (audit §2).

Life Engine's ecosystem plan (ADR-PLAT-017, `Architecture/Life-Engine-Ecosystem-v1.md`) wants Capital, Caputronic, Legal escrow and agent-to-agent payments to reuse "the same execution substrate"; Engine v1 wants to extract CryptoBot's reliability pattern (E7) and a policy/governance engine (E5). Today none of them can consume CryptoBot without inheriting the trading vertical.

The mandate (2026-09-21) sets the thesis: *AI agents may propose economic actions; they must never receive unrestricted signing authority.* And the constraints: audit first, no big bang, preserve the hackathon demo (freeze 08/10, submit ≤ 10/10), MAINNET = DENY, keep signer/validator isolation, keep metric names, routes and persisted states.

## Decision

1. **Separate the code into three modules by responsibility, inside the current repository and the current JVM, in that order of stability:**
   - `trusted-execution-core` — intent, policy, simulation, approval, attestation, signing client, submission, reconciliation, idempotency, retry, DLQ, receipts, audit, and the *ports* (`ChainSimulationPort`, `ChainExecutionPort`, `ChainObservationPort`, `AssetPort`, `MarketDataPort` as an input, `AnchorPort`). **The core knows no strategy, no portfolio and no LLM.** It accepts an `ExecutionIntent` and returns an `ExecutionReceipt`.
   - `solana-execution-adapter` — the JSON-RPC client, transaction builder, simulation, confirmation, chain observer, asset reads, memo/anchor. Solana-first; no multi-chain abstraction beyond the ports.
   - `cryptobot-trading` — market data, signals, strategy (REBALANCE as the one reference strategy), portfolio, position, trading risk, the LLM advisor, and the **intent producer** that turns a plan into an `ExecutionIntent`. It never calls the signer, the validator or the chain adapter's write side.
   - `signer/` and `validator/` stay **separate processes** with their own keys, tokens and policy copy: that isolation is the security property the mandate asks to preserve, and the audit confirms it is real (audit §6, §8, §9).
2. **Phase 1 realises the boundary with Java packages and an ArchUnit test, not with a Maven split or a new service.** Maven modules are a phase 9 decision after the rules have held for a release. No service is added; no service is removed.
3. **The `ExecutionIntent v1` (mandate §4) is derived from `TradingIntent` (KAN-435), not created next to it**, and becomes the only input of the core (KAN-457 is the story). The policy engine's `(I)` is built from the intent; `POLICY_BOUND` and `SLIPPAGE_WITHIN_MAX` verify the producer's values. The proposal row keeps `intent_hash`; creation becomes idempotent by it.
4. **Persisted states, metric names, routes, audit event types, receipt kinds and the receipt hash domain are not renamed.** New API operations (`/intents`, `/executions`, body-based `/receipts/verify`) are additive aliases. `ESCALATE` remains the persisted value; `APPROVAL_REQUIRED` is a documented alias in the contract.
5. **Execution policy, trading risk and agent governance are three different things and stay separate** (mandate §18; Ecosystem-v1 E5): execution policy (network, mainnet, per-tx/daily limits, allow-lists, slippage, simulation, price integrity, velocity, approval tier) lives in the core; trading risk (concentration, drawdown, liquidity, confidence) lives in the vertical and reaches the core only as intent constraints; governance of agents/tools (E5, KAN-528) stays in Engine and is *not* fused with either.
6. **The validator is kept and hardened, not redesigned**: it must receive and decode the unsigned transaction, cross lamports/destination/program with the intent facts and its own allow-lists, use its own clock, and include the approval hash in the attestation (audit §9). The signer contract stays byte-level: one shape, cap, allow-list, attestation required; widening it (memo + ComputeBudget) is allowed only with exact instruction whitelists and only if the E2E proves the benefit.
7. **Layer classification (required by ADR-PLAT-017):** `trusted-execution-core` and `solana-execution-adapter` are **Engine-layer capabilities incubated inside the CryptoBot repository**; `cryptobot-trading` is the **vertical**. Nothing moves out of the repository until phase 9, which needs an ADR-ENG aligning with E5/E7 and an ADR-PLAT for the receipt ledger.
8. **Events:** the core defines the semantic event vocabulary (`IntentCreated … ReceiptIssued`, IDs not payloads) over the existing outbox; the transport (Kafka/RabbitMQ/NATS, Sebastián's stated preference for a real broker for CryptoBot) is decided in E7 (KAN-530) after 12/10, not here.
9. **Hackathon golden path:** the executable intent stays "rebalance SOL 70 → 50 %" (one `SystemProgram.transfer` to the vault). "Buy 50 USDC of SOL" is a swap the byte-level signer cannot bound before 12/10; it is shown as a canonical intent that policy refuses (no allowed protocol), and becomes a phase 7+ feature.
10. **MAINNET = DENY** at every layer until an independent readiness gate exists (last story of phase 9); no phase weakens a check for a demo.

## Alternatives considered

| Alternative | Why not |
|---|---|
| **A. Keep one module, add the missing features** (wire the intent, harden the validator, extend the receipt) without a boundary | Fastest for the hackathon, but every future vertical would inherit `RebalancePlanner`, the oracle and the advisor; Engine E5/E7 could not consume the core; the audit shows the coupling grows (7 direct RPC imports, `Wallet` → adapter). Rejected as the end state; it is, however, what phases 0.5 and 2 look like on the way. |
| **B. Extract the core into a new repository/service now** (`life-engine-execution-core`) | Big bang against the mandate (§33), breaks the demo freeze, duplicates deploy/GitOps/CI work for no security gain (signer/validator are already separate processes), and pre-empts the Engine ADRs that decide where it lives. Deferred to phase 9. |
| **C. Make the Solana program (`intent-authority`) the enforcement layer** and shrink the off-chain validator | The program is implemented and tested but not deployed and not wired (audit §14); deploying needs the Solana CLI on a host and a wiring decision; it moves no funds, so the signer would still be the barrier. USE LATER (mandate §21). |
| **D. Replace signer/validator with a custodial policy wallet (Turnkey/Privy/Crossmint/Squads)** | Solves key custody (MPC/TEE) and SPL parsing better than we do, but removes exactly the composition the audit and the pitch identify as the differentiator (pinned independent validator + attestation over bytes + signed receipts/DAG + anchor + reconciliation/DLQ, self-hosted). Squads v4 spending limits are a candidate *additional* on-chain layer in phase 9, not a replacement. |
| **E. Separate trading from the core by moving the core into Runtime** (Runtime V3 "Execution Engine", KAN-253) | Name collision only: Runtime's execution engine is about durable runs, not economic execution; ADR-ENG-002 says Runtime never executes processes and only produces intents. Rejected. |
| **F. Rename states/routes/metrics to the mandate's vocabulary** (`APPROVAL_REQUIRED`, `/intents`, `execution_*`) | Breaks 2 dashboards (82 panels), 3 alerts, the UI timeline, 3 smoke/demo scripts and the anchored receipts (audit §13, §22). Aliases instead. |

## Consequences

- **Positive:** one execution substrate with an explicit contract (`ExecutionIntent` in, `ExecutionReceipt` out) that Capital, Caputronic, escrow and agent payments can target without touching the core (audit §22 fit analysis: all four fit the intent model; three of four need SPL/program support in the *adapter and signer*, not in the core); the hackathon demo keeps working at every phase; the security barrier that already works (signer by bytes) is untouched while the weak one (validator over declared facts) gets teeth; Engine E5/E7 get a concrete contract to consume instead of a vertical to untangle.
- **Negative / cost:** ~20 stories across 9 phases; one large mechanical PR (phase 1) that will conflict with anything open on `ExecutionService`/`PolicyEngine`; a second attestation schema version during the validator rollout; additive migrations `V11+` (after PR #27 renumbers); more tests to maintain (Testcontainers, property tests, ArchUnit).
- **Neutral:** no new runtime component; no change to deploy/GitOps topology; the same three images; the same env vars.

## Security implications

- The private key remains in the signer process only; no phase introduces a second holder. The uat-compose delivery of that key as an environment variable is a phase 0.5 fix (file secret), independent of this ADR.
- The validator's attestation grows (bytes decoded, approval hash, own clock) — this *adds* properties; the signer keeps requiring it. The unpinned `VALIDATOR_POLICY_HASH` in UAT is a configuration defect fixed in phase 0.5 and asserted by the smoke thereafter.
- Destination binding between approval and execution (today only the signer's allow-list protects it) is added in phase 4 and is the highest-value single change.
- Separation of duties (approver ≠ requester for escalated tiers; distinct authority for `approve`) closes the "same OPERATOR does everything" gap; the S2S token's derived authorities (open question P-1) must be verified in Auth before it is declared done.
- Mainnet stays refused in three processes by three flags defaulting to `false`; the ADR adds a genesis-hash check in the service so the `cluster` label stops being purely declarative.
- Nothing in the plan gives the LLM or the strategy a path to the signer: the ArchUnit rules make that a build failure, not a review comment.

## Migration

Phases 0 → 9 as in audit §25, each deployable, each rolled back by digest, all migrations additive. Order of merge for the first weeks: PR #30 (KAN-582) → phase 0.5 quick wins (INFRA gates) → phase 1 (boundaries, zero semantic diff, before 08/10 only if `scripts/demo/run.sh`, `test-run.sh`, 517/33/28 tests and the UAT smoke stay green) → freeze → phase 2 (KAN-457) from 13/10.

## Rejected approaches (recorded so they are not re-discussed)

- A new token, program or L1 (mandate §41; vault claims policy).
- Kafka/RabbitMQ/NATS in this ADR (E7 decides; the core only exports the event contract).
- Event-sourcing rewrite of `action_proposal`; a separate `execution` table (`executionId = proposalId`).
- Multi-chain adapters before a second chain exists.
- Running the core inside Runtime or inside Dev Agent.
- Calling the result a "protocol" or "standard" before a public spec and a second implementer exist (vault `Products/CryptoBot-Hackathon-Pitch-2026-09-20.md §6`).
