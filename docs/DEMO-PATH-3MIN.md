# Demo path — 3 minutes

**CryptoBot — Proof of Value for Autonomous Agents.** The full demo, told as one story:
**real operation → accepted software → contribution attribution → value created → revenue → contributors paid on Solana**,
and only then: open `/value` and show the verifiable receipts.

Every id, hash and tx comes from **one** run of `scripts/demo/pov-e2e.sh` (report `out/pov-e2e-<ts>.md`); screens are
cryptobot-ui routes opened signed in with `scripts/demo/ui-url.sh --path <route>`. The story order is not the script's step
order — the cut rearranges shots of the same run. A step the run skipped is cut from the video, never faked.

| t | Beat | Shot (from the run) | Screen | What the viewer sees | Voice-over cue |
|---|---|---|---|---|---|
| 0:00 | — | — | title card / commit graph | "who actually created this value?" | "AI can create value. Who created it?" |
| 0:10 | **Real operation** | STEP 6/9 | `/live/:proposalId` · `/tower` · explorer | CryptoBot proposes an operation: intent → policy → human approval → timelock → independent validator → isolated signer → finalized devnet tx → reconciled | "CryptoBot is the first real case. It acts on Solana, and it never holds a key." |
| 0:35 | **Accepted software** | STEP 1/9 | terminal · `/value/identities/sebas` | task `TASK-042` "Improve CryptoBot opportunity detection", specified by a human (SPECIFIER + ARCHITECT) | "A human specifies the work…" |
| 0:45 | | STEP 2/9 | terminal · `/value/identities/dev-agent-17` | an agent implements it: commit, PR, image digest; knowledge used; compute receipt | "…an agent implements it. Nothing counts yet." |
| 0:55 | | STEP 3/9 | terminal · `/value/:id` | five stages from a measured release verdict: MERGED · BUILT · DEPLOYED · RUNNING · ACCEPTED; a missing stage is a `422` | "It counts only when it is merged, built, deployed, running and accepted." |
| 1:15 | **Contribution attribution** | STEP 4/9 | `/value/:id` drill-down | contributors with roles and Contribution Units (100 per event, equal split); knowledge assets by content hash with their creators credited; compute cost shown next to value, never as value | "Every contributor is named: humans, agents, knowledge, compute." |
| 1:35 | **Value created** | STEP 4/9 anchor | explorer · `/proof/:root` | the ValueEvent is a signed receipt; its Merkle root is in the memo `ir/1 root=…`; the inclusion proof folds to it; `verified: true` | "The value is recorded on Solana. Anyone can verify it." |
| 1:55 | **Revenue** | STEP 7/9 | `/value/revenue/:id` | revenue linked to the operation, labelled *simulated economic result*; split 20% contributor pool · 5% protocol fee · 75% retained treasury; REVENUE_EVENT anchored | "When the agent's work earns revenue, a fixed public policy shares it — predictable and auditable, not a black box." |
| 2:15 | **Contributors paid on Solana** | STEP 5/9 + 8/9 | `/value/:id` payouts · `/value/revenue/:id` payouts · `/value/treasury` | one finalized devnet transfer per contributor wallet: the immediate reward, then the revenue pool pro rata by historical units; the agent's treasury | "…and the contributors are paid on Solana, by the units they earned." |
| 2:35 | **The receipts** | STEP 9/9 · final screen | `/value` · `/value/ledger` · explorer | open `/value`: every event, anchor and payout links to a finalized devnet tx; the units ledger adds up; **VALUE GENERATED (simulated: yes) · ATTRIBUTION** | "Software should remember who created its value." |
| 2:55 | — | — | end card | repo, license (Apache-2.0), devnet only | "Contribution Units are attribution, not equity." |

## Before recording

- Wallet: the real operation moves ≥ 21 % of the demo wallet's SOL to the vault (a wallet of the demo), and the rest of a run
  spends ≈ 0.02 SOL (measured 2026-09-30): fund the wallet before the take.
- Acceptance: the release-truth verdict must pass (JSON, exit 0); the run binds the artifact to the image it measured.
- Hygiene: neutral task ids only (the script refuses tracker ids); no lab hostnames, tokens or key paths on screen.
