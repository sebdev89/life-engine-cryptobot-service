# Demo path — 90 seconds

The short cut of the Proof of Value demo. One run of `scripts/demo/pov-e2e.sh` produces every id, hash and tx shown here (report
`out/pov-e2e-<ts>.md`); the screens are routes of cryptobot-ui opened signed in with `scripts/demo/ui-url.sh --path <route>`.
Aligned with the submission draft (§5, 90 seconds). Nothing is shown that the run did not produce: a step the run skipped is cut, not faked.

| t | Step (`pov-e2e.sh`) | Screen | What the viewer sees |
|---|---|---|---|
| 0–10 | — | title card | "AI can create value. Who created it?" |
| 10–25 | STEP 1–2 task → DevAgent | terminal (STEP 1/9, 2/9) · `/value/identities/dev-agent-17` | task `TASK-042` specified by `sebas`; commit, PR and image digest by `dev-agent-17` |
| 25–40 | STEP 3 acceptance | terminal (STEP 3/9) · `/value/:id` stage chips | MERGED · BUILT · DEPLOYED · RUNNING · ACCEPTED, each from a release-truth verdict; source `release-truth`, environment `uat-k8s` |
| 40–55 | STEP 4 ValueEvent on Solana | `/value` → `/value/:id` → explorer | row ANCHORED; memo `ir/1 root=…` in the explorer equals the root of `/proof`; `verified: true` |
| 55–70 | STEP 5 immediate payout | `/value/:id` payouts | one CONFIRMED devnet transfer per contributor wallet; "devnet SOL stands in for stablecoin settlement" |
| 70–85 | STEP 9 units + reputation | `/value/ledger` · `/value/identities/dev-agent-17` | units per identity (total = events × 100); the agent's accepted outcomes and history |
| 85–90 | final screen | terminal (VALUE GENERATED / ATTRIBUTION) · `/value` | value generated (labelled simulated), contributors, units, tx links |

Revenue (STEP 7) and the CryptoBot operation (STEP 6) belong to the 3-minute cut ([`DEMO-PATH-3MIN.md`](DEMO-PATH-3MIN.md)).

## Recording checklist

1. Stack up with the UI (`docker-compose.demo.yml --profile ui`), demo wallet funded (≥ 0.05 SOL for a `--skip-op` run).
2. `release-truth.sh uat cryptobot --json > rt.json` from the deploy checkout, then
   `scripts/demo/pov-e2e.sh --task "Improve CryptoBot opportunity detection" --task-id TASK-042 --acceptance-json rt.json`.
3. Open the routes printed under each `UI` line, in order. Never show a tracker id, a hostname of the lab, a token or a key path.
