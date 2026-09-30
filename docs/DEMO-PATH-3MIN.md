# Demo path — 3 minutes

The full Proof of Value demo: every step of `scripts/demo/pov-e2e.sh` on screen. Aligned with the submission draft (§5 and §6, video
script). Every id, hash and tx comes from one run (report `out/pov-e2e-<ts>.md`); screens are cryptobot-ui routes opened signed in with
`scripts/demo/ui-url.sh --path <route>`. A step the run skipped is cut from the video, never faked.

| t | Step (`pov-e2e.sh`) | Screen | What the viewer sees | Voice-over cue |
|---|---|---|---|---|
| 0:00 | — | commit graph | "who actually created this value?" | "AI can create value…" |
| 0:15 | STEP 1/9 human defines task | terminal · `/value/identities/sebas` | task `TASK-042` "Improve CryptoBot opportunity detection", spec by `sebas` (HUMAN, SPECIFIER + ARCHITECT) | "A task is specified by a human…" |
| 0:30 | STEP 2/9 DevAgent implements | terminal · `/value/identities/dev-agent-17` | commit, PR link, image digest; roles; knowledge `strategy-knowledge@3`, `production-acceptance-model@1`; compute receipt of `compute-node-8` (labelled estimated unless measured) | "…and implemented by an agent. Nothing counts yet." |
| 0:50 | STEP 3/9 acceptance | terminal · `/value/:id` | five stages, each from a release-truth verdict (commit/repository → MERGED, image → BUILT, revision/config → DEPLOYED, health → RUNNING, functional acceptance → ACCEPTED); a missing stage is a 422 | "It counts when it is merged, built, deployed, running and accepted…" |
| 1:10 | STEP 4/9 ValueEvent | `/value/:id` drill-down | contributors with units, knowledge assets, compute receipt ("compute cost ≠ economic value") | "Then we issue a ValueEvent…" |
| 1:30 | STEP 4/9 anchor | explorer · `/proof/:root` | memo `ir/1 root=…` = `/proof` root; inclusion proof folds to it; `verified: true` | "Anyone can verify it." |
| 1:45 | STEP 5/9 immediate reward | `/value/:id` payouts | one devnet tx per wallet, balances before/after; UNFUNDED shown as such if a wallet is missing | "Contributors are paid immediately…" |
| 2:00 | STEP 6/9 CryptoBot operation | `/live/:proposalId` · `/tower` | intent → policy → approval → execution → EXECUTED, tx in the explorer. Cut if the run used `--skip-op` (then 2:00–2:15 shows the existing trusted-execution scene, real on devnet) | "CryptoBot is our first economic agent." |
| 2:15 | STEP 7/9 RevenueEvent | `/value/revenue/:id` | revenue from that operation, labelled *simulated economic result*; split 20 % contributors · 5 % protocol fee (recorded) · rest retained; REVENUE_EVENT anchored | "When its work produces revenue…" |
| 2:30 | STEP 8/9 historical distribution | `/value/revenue/:id` payouts · `/value/treasury` | pool split by the units of the linked ValueEvent; per-contributor totals (reward + revenue); treasury of `cryptobot-001` | "…the history decides who shares in it." |
| 2:40 | STEP 9/9 reputation + units | `/value/ledger` · `/value/identities/:id` | ledger totals add up; agents' accepted outcomes | "Contribution Units are an attribution primitive, not equity." |
| 2:50 | final screen | terminal · `/value` | **VALUE GENERATED x SOL (simulated: yes) · ATTRIBUTION: sebas · dev-agent-17 · strategy-knowledge@3 · compute-node-8 · review-agent-3 · treasury · protocol** | "Software should remember who created its value." |

## Before recording

- Wallet: a `--skip-op` run spends ≈ 0.02 SOL (measured 2026-09-30); the real operation moves ≥ 21 % of the demo wallet's SOL to the
  vault (a wallet of the demo), so fund the wallet before a take with STEP 6.
- Acceptance: `release-truth.sh uat cryptobot --json` must exit 0 (7/7 PASS); the run binds the artifact to the image it measured.
- Hygiene: neutral task ids only (the script refuses tracker ids); no lab hostnames, tokens or key paths on screen.
