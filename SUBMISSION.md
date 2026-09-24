# CryptoBot — Safe Execution Protocol for AI Agents on Solana

*A policy-controlled execution layer that lets AI agents safely transact on Solana. CryptoBot is
its reference implementation.*

**The problem.** AI agents are about to hold wallets. Today an "agent that transacts" is a
language model with a private key and an RPC endpoint. A hallucination, a prompt injection or a
dropped RPC moves funds — sometimes twice — with no proof of what happened.

**The protocol.** The model never signs. It emits a bounded *intent*. The exact unsigned bytes
are simulated on chain; a versioned policy `R_v` (hashed as `H_R`, unknown ⇒ deny) with a
multi-source price oracle returns ALLOW / DENY / REVIEW; a human approves under a timelock; an
**independent validator**, its own hash-pinned policy, re-derives the verdict and attests those
bytes; an **isolated signer** with bounded authority refuses anything without that attestation;
the transaction goes to Solana; a reconciler asks the chain, dead-letters ambiguity and retries
only a never-seen, expired signature under the same operation id; every step leaves a signed,
content-addressed receipt whose `verify` re-runs the policy engine; receipts form a DAG anchored
by a Merkle root in a devnet memo.

**Proven, not promised.** 16 confirmed transfers on devnet, from local runs and our UAT
(Compose and Kubernetes, three containers from CI by digest), 0 failed, 0 double executions —
one recovered after cutting the RPC at broadcast time, the chain shows the first signature never
landed. Adversarial intents blocked by price deviation, stale oracles, an unauthorized mint and
an altered receipt (one flipped byte, `verify` returns `valid=false`), each with a receipt that
verifies and **reproduces**. Four consecutive end-to-end runs on the same commit (`5ff3d40`), no
intervention, all **PASSED** — two against a local validator, two against live devnet — worst
case 3m46s. Adversarial benchmark: 10,000 intents, 3,000 attacks in 13 classes, **0 violations
executed**; invariants I1–I7 hold. Reproducible demo in ~3 minutes: `scripts/demo/run.sh`.

**How it uses Solana.** JSON-RPC (`simulateTransaction`, `sendTransaction`,
`getSignatureStatuses`), SPL Memo anchoring, Ed25519 attestations. A native `intent-authority`
program (policy / nonce / receipt PDAs, 29 tests) is written, not yet deployed.

**What we are not.** Not a trading bot, not a policy wallet or multisig on its own, not
"deterministic AI". Mainnet is fail-closed at three layers, all default off; no custody, no
autonomous execution, `SystemProgram` transfer only. Life Engine (auth, runtime, observability)
pre-exists; the Solana execution layer is hackathon work.

---

*(Below this line: supporting evidence and links, not part of the ≤ 400-word submission text
above.)*

## Devnet signatures — Act 1, four consecutive runs (2026-09-22, `5ff3d40`)

Two of the four confirmed transfers from the run described above, verifiable on the explorer:

- Run 3 (devnet):
  [`258WrABk4ZwmXbKcacrUsv8roUBbkriwvahEhoj7hywP4PPxvrDsUEdNSyg4a7vLQbmVBoo962GHwnRraWvvPuzr`](https://explorer.solana.com/tx/258WrABk4ZwmXbKcacrUsv8roUBbkriwvahEhoj7hywP4PPxvrDsUEdNSyg4a7vLQbmVBoo962GHwnRraWvvPuzr?cluster=devnet)
- Run 4 (devnet):
  [`3urAaP67PmBnxrhyMuLF7WuHtV9b5pqy8nsKrnLSewqQ6yi2EMtXxE4ZAgdPNq82gZm66MSFFzyMDn7L8tgQdSDU`](https://explorer.solana.com/tx/3urAaP67PmBnxrhyMuLF7WuHtV9b5pqy8nsKrnLSewqQ6yi2EMtXxE4ZAgdPNq82gZm66MSFFzyMDn7L8tgQdSDU?cluster=devnet)

Full report of the four runs: `Operations/KAN-569-Demo-Runs-2026-09-22.md` (vault).

## The evidence behind "reference implementation"

*(This section is a reference / link, not part of the ≤ 400-word submission text above.)*

This is not a marketing label: it is backed by a 30-section engineering audit of the running
code — [`docs/architecture/TRUSTED-AGENT-EXECUTION-AUDIT.md`](docs/architecture/TRUSTED-AGENT-EXECUTION-AUDIT.md)
(every claim cited `path:line` or a command with its output) — and its ADR,
[`docs/architecture/TRUSTED-AGENT-EXECUTION-ADR.md`](docs/architecture/TRUSTED-AGENT-EXECUTION-ADR.md),
which records the ten decisions that keep the security boundary intact while the code splits
into reusable modules (trusted-execution-core, solana-execution-adapter, cryptobot-trading),
including the explicit choice not to rename any persisted state, metric, route or receipt kind,
and that mainnet stays `DENY` until an independent readiness gate exists (decision 10). Both are
linked from the repository's own architecture docs, not asserted only here.
