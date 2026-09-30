# CryptoBot — Trusted Agent Execution on Solana

CryptoBot is a reference implementation of Trusted Agent Execution on Solana. AI agents can already
decide what financial actions to take. CryptoBot provides the layer that safely turns those decisions
into real on-chain execution through policy controls, idempotency, isolated signing, reconciliation,
failure recovery and verifiable proofs.

## The problem

An LLM should never hold a private key. An agent that signs and broadcasts on its own produces
duplicate executions, dangerous retries after timeouts and partial failures, and no evidence of what
actually happened. Deciding is the easy part; moving the money exactly once is not.

## What runs today (`main` @ `777672c`)

```
Agent → Intent → Policy / Approval / Timelock → Trusted Execution → Solana devnet
   (idempotency · outbox · DLQ · retry · isolated signer · reconciliation · signed receipt · Merkle proof)
```

- Real transfers on Solana devnet, reported only once **finalized**.
- Policy check, human approval and timelock before anything is signed.
- Idempotency: one operation id from intent to chain; transactional outbox.
- Dead-letter queue with requeue and idempotent retry; reconciliation against the chain.
- Isolated signer process, gated by an independent validator — the agent never holds a key.
- Signed, content-addressed receipts forming a DAG; a Merkle root anchored on devnet, with inclusion proofs.
- End-to-end demo: **5/5 acts passed** against live devnet (`scripts/demo/run.sh`).

## The demo, in two scenes

1. **Trusted execution:** intent → policy → approval → execute → finalized on Solana → reconciled → proof.
2. **Failure and recovery:** RPC cut at broadcast → dead letter → retry → recovered — one transaction
   on chain, no duplicate.

## What it is not yet

No multi-tenant organizations, no public API or SDK, no SPL/USDC, no swaps: SOL transfers to an
allow-listed vault only. The on-chain `intent-authority` program is written and tested, not deployed.
Mainnet is closed by default. Not a trading bot: the decision can come from any agent or model.

## Stack

Spring Boot · Postgres · Solana devnet (JSON-RPC, SPL Memo) · separate signer and validator processes · Angular UI.

## Links

- Service: `github.com/sebdev89/life-engine-cryptobot-service` — *[public link / access for judges: TBD]*
- UI: `github.com/sebdev89/life-engine-cryptobot-ui` — *[public link / access for judges: TBD]*
- Demo: [`scripts/demo/README.md`](scripts/demo/README.md) · video: *[TBD]*
- Architecture: [`docs/architecture/TRUSTED-AGENT-EXECUTION-AUDIT.md`](docs/architecture/TRUSTED-AGENT-EXECUTION-AUDIT.md)
- Devnet evidence (demo run on `777672c`, 2026-09-29):
  [execution](https://explorer.solana.com/tx/3ofZGCjbMXgjHY6iB8w7VSXvr6pHvajzayS7sSJox1yZxeDwPHAyb17xs8cXzUf7Us8GTbqc5M3tVUL85pGfewmh?cluster=devnet) ·
  [recovered retry](https://explorer.solana.com/tx/65g1juW9qSPuM3jNacoq12QjMa1cVZv2DCCoybCw7vA74oBYZEpeqgwkZHRkDPv1g21iXQZLkd2Uu7xdp8K13WEq?cluster=devnet) ·
  [Merkle anchor](https://explorer.solana.com/tx/4xBC6UTVghYajPwWQKLe1mdByKXahah2YFdQgiRQTArhvJkLMoshWmbZTLk4jwWaGuUXTLL8sQULeiJ53XSPPvjY?cluster=devnet)
