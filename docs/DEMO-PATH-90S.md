# Demo path — 90 seconds

**Proof of Value — Verifiable Value Creation on Solana.** The short cut, told as one story:
**real operation → accepted software → contribution attribution → value created → revenue → contributors paid on Solana**,
and only then: open `/value` and show the verifiable receipts.

Every id, hash and tx on screen comes from **one** run of `scripts/demo/pov-e2e.sh` (report `out/pov-e2e-<ts>.md`); screens
are cryptobot-ui routes opened signed in with `scripts/demo/ui-url.sh --path <route>`. The story order is not the script's
step order — the cut rearranges shots of the same run. Nothing is shown that the run did not produce: a step the run
skipped is cut, not faked.

| t | Beat | Shot (from the run) | Screen | What the viewer sees |
|---|---|---|---|---|
| 0–8 | — | — | title card | "AI can create value. Who created it?" |
| 8–22 | **Real operation** | STEP 6/9 | `/live/:proposalId` → explorer | CryptoBot, the first real case: intent → policy → approval → independent validator → isolated signer → finalized devnet tx. The agent never holds a key. |
| 22–36 | **Accepted software** | STEP 1–3/9 | terminal · `/value/:id` stage chips | a task specified by a human, implemented by an agent (commit, PR, image digest); it counts only after MERGED · BUILT · DEPLOYED · RUNNING · ACCEPTED |
| 36–48 | **Contribution attribution** | STEP 4/9 | `/value/:id` | every contributor with its role and Contribution Units; knowledge assets by content hash (their creators credited); compute recorded, never counted as value |
| 48–58 | **Value created** | STEP 4/9 anchor | explorer · `/proof/:root` | the ValueEvent's root in the memo `ir/1 root=…` equals the root of `/proof`; `verified: true` |
| 58–68 | **Revenue** | STEP 7/9 | `/value/revenue/:id` | revenue linked to the operation, labelled *simulated economic result*; 20% contributor pool · 5% protocol fee · 75% retained |
| 68–80 | **Contributors paid on Solana** | STEP 5/9 + 8/9 | `/value/:id` and `/value/revenue/:id` payouts | one finalized devnet transfer per contributor wallet, pro rata by Contribution Units |
| 80–90 | **The receipts** | final screen | `/value` → explorer | open `/value`: every event, payout and anchor links to a finalized devnet transaction anyone can verify |

## Recording checklist

1. Stack up with the UI (`docker-compose.demo.yml --profile ui`). The real operation (STEP 6) moves 21–40 % of the demo
   wallet's SOL to the demo's own vault: fund the wallet before the take.
2. Measure acceptance with the release-truth tooling (JSON verdict, exit 0), then
   `scripts/demo/pov-e2e.sh --task "Improve CryptoBot opportunity detection" --task-id TASK-042 --acceptance-json <verdict.json>`
   (no `--skip-op`: this cut needs the operation).
3. Open the routes printed under each `UI` line and cut them into the order above. Never show a tracker id, a lab hostname,
   a token or a key path.
