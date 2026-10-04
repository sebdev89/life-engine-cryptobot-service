# CryptoBot — Proof of Value for Autonomous Agents

Proof of Value records who created an accepted software outcome, anchors that record on Solana, and pays the
contributors — humans, agents, knowledge and compute — from the value it creates. CryptoBot, a trusted-execution
agent on Solana, is the first real case that proves the protocol.

> **AI can create value. Proof of Value makes sure we remember who created it.**

## The problem

Agents already write, ship and operate software. Commits and PRs say who typed something, not who created value: nothing
records which humans, agents, knowledge and compute produced an outcome that was actually **accepted** in production, and
nothing pays them from what it earns. And an agent that moves money on its own should never hold a private key.

## The story (what the demo shows)

1. **Real operation** — CryptoBot executes a real devnet operation through policy, approval, an independent validator and
   an isolated signer; the agent never holds a key.
2. **Accepted software** — a task is specified by a human and implemented by an agent; it only counts after five
   measured stages: MERGED → BUILT → DEPLOYED → RUNNING → ACCEPTED.
3. **Contribution attribution** — a ValueEvent names every contributor with its role, the knowledge assets used (by
   content hash) and the compute consumed, and assigns 100 Contribution Units.
4. **Value created** — the ValueEvent is a signed receipt whose Merkle root is anchored on Solana; anyone can verify it.
5. **Revenue** — a RevenueEvent linked to the ValueEvents shares an economic result by historical units.
6. **Contributors paid on Solana** — one devnet transfer per contributor wallet, each validated, signed and finalized.

Then `/value` shows the verifiable receipts: each payout, anchor and proof links to a finalized devnet transaction.

## Distribution policy — public and fixed

**20% contributor pool · 5% protocol fee · 75% retained treasury.** Predictable and auditable, not a black box:
100 Contribution Units per accepted outcome, split equally per contribution (`pov/equal-split/v1`); the creator of each
knowledge asset used is credited automatically as `KNOWLEDGE_PROVIDER`; rewards and revenue are paid pro rata by
Contribution Units. No AI decides the shares.

## What is verified today (Solana devnet)

V1–V9 are **ACCEPTED**: running on the devnet demo stack built from `main`, with finalized devnet transactions as evidence
(V8, the treasury, is a read model over those payouts; full table and links in [`README.md`](README.md#whats-real)). V9 — the end-to-end run — completed **9/9 steps with a
real CryptoBot operation** on 2026-09-30, all finalized on devnet:

- [ValueEvent anchor](https://explorer.solana.com/tx/51nco1gL2wxNPT2Mi8T4PMSMwgxpPjSbae7djBrGZoYjyyWuRY4uc33aknjD514ixBhgXEwB1f2224PnoWjQCmKY?cluster=devnet)
- [CryptoBot operation](https://explorer.solana.com/tx/xYpJkKrPa96jLbpJHEe5kBtNUVSKeiBdrDPThg4J8UfW9uTE4w5sE846tR5umc7qK3vM2AMcJ5FkUHvP73pBARF?cluster=devnet)
- [RevenueEvent anchor](https://explorer.solana.com/tx/Bcq8cqYbUndGkzi74DtrmuZXGpLV1KQPd98k6SDXSjzjbVDBbFcx5XdbYiXp5gkLj7RVVe3NL2ofqdDqHNDJpT3?cluster=devnet)

The operation is real; the **revenue amount is a simulated economic result** (`simulated=true`), labelled as such
everywhere it appears.

Known gap: the OCI build label (`life-engine.commit`) of the image running in the UAT pod is still stale; the fix (labels
stamped at build time) is pull request #54 and reaches the pod with its next deploy, after the submission. The image
itself is identified by digest.

## What it is not yet

Devnet only — devnet SOL stands in for stablecoin settlement (no SPL/USDC yet). One signer, one validator, no multisig.
The on-chain `intent-authority` program is written and tested, not deployed. Mainnet is closed by default. Contribution
Units are an attribution primitive, not equity and not a promise of financial return; there is no token. Full list:
[`README.md` → Limitations](README.md#limitations).

## Stack

Spring Boot · Postgres · Solana devnet (JSON-RPC, SPL Memo, SystemProgram transfers) · separate signer and validator
processes · Angular UI.

## Links

- Repo access: **public (Apache-2.0)**
- Service: https://github.com/sebdev89/life-engine-cryptobot-service
- UI: https://github.com/sebdev89/life-engine-cryptobot-ui
- Demo, one command: `scripts/demo/hackathon.sh` ([`README.md` → Try it](README.md#try-it)) · details: [`scripts/demo/README.md`](scripts/demo/README.md) · video cuts: [`docs/DEMO-PATH-90S.md`](docs/DEMO-PATH-90S.md),
  [`docs/DEMO-PATH-3MIN.md`](docs/DEMO-PATH-3MIN.md) · video: *[TBD]*
- Protocol and API: [`docs/PROOF-OF-VALUE.md`](docs/PROOF-OF-VALUE.md) · trusted execution (CryptoBot):
  [`docs/TRUSTED-AGENT-EXECUTION.md`](docs/TRUSTED-AGENT-EXECUTION.md)
