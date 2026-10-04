# Demo path — the video (2:38)

**CryptoBot — Proof of Value for Autonomous Agents.** The full demo, told as one chain:
**Intent → Strategist → Guardian / Risk → Operator → Solana execution → AcceptanceProof → ValueEvent → Contribution Units →
Value Distribution → Reputation.**

Every id, hash and tx comes from **one** run of `scripts/demo/hackathon.sh` (it drives `pov-e2e.sh`; report
`out/pov-e2e-<ts>.md`) **with** the real operation, on a fresh stack with a clean database; screens are cryptobot-ui routes
opened signed in with `scripts/demo/ui-url.sh --path <route>`. The cut follows the chain, not the script's `STEP n/9`
order: it rearranges shots of the same run. A step the run skipped is cut from the video, never faked.

| # | Link | t | Shot (from the run) | Screen | Voice-over |
|---|---|---|---|---|---|
| 1 | Problem | 0:00–0:10 | — | title card over a commit graph: "who created this value?" | "AI agents now act on Solana and ship software. Who created that value? Proof of Value proves it, and pays for it." |
| 2 | **Intent** | 0:10–0:21 | STEP 6/9 | `/live/:proposalId` — the proposal header and *Intent → plan* | "This is CryptoBot, an agent on Solana devnet. Its intent: a rebalance, moving part of its SOL to its own vault." |
| 3 | **Strategist** | 0:21–0:33 | STEP 6/9 | `/live/:proposalId` — *Intent → plan* (exact lamports) and *Simulation (exact bytes)* | "A deterministic planner turns the intent into exact amounts, and the exact transaction bytes are simulated first." |
| 4 | **Guardian / Risk** | 0:33–0:47 | STEP 6/9 | `/live/:proposalId` — *Risk + policy* (13 rules · `R_v` · `H_R`), verdict and *Timelock* | "Then the guardian: thirteen policy rules under a versioned policy hash. Above the autonomous limit, the verdict escalates and adds a timelock." |
| 5 | **Operator** | 0:47–1:01 | STEP 6/9 | `/live/:proposalId` — approval → independent validator → isolated signer | "The operator approves. An independent validator re-derives the verdict, and an isolated signer signs only those bytes. The agent never holds a key." |
| 6 | **Solana execution** | 1:01–1:13 | STEP 6/9 | `/tower` (the operation as VERIFIED) → explorer of the operation (`SystemProgram` transfer, finalized) | "Finalized on devnet. In the explorer, the SOL leaves the agent's wallet for its vault." |
| 7 | **AcceptanceProof** | 1:13–1:31 | STEP 1–3/9 | terminal (task `TASK-042`, commit / PR / image, the five stages) → `/value/:id` stage chips | "That agent runs on software another agent shipped. It only counts once it is merged, built, deployed, running and accepted. Five stages, measured, not declared. Miss one, and nothing is recorded." |
| 8 | **ValueEvent** | 1:31–1:45 | STEP 4/9 | `/value/:id` — signed receipt, Merkle proof, `verified: true` → explorer of the anchor memo `ir/1 root=…` | "The accepted outcome becomes a ValueEvent: a signed receipt whose Merkle root is written to Solana. Anyone can verify it." |
| 9 | **Contribution Units** | 1:45–2:02 | STEP 4/9 + 9/9 | `/value/:id` contributors with roles, assets by hash, compute "cost is not value" → `/value/ledger` | "Who created it? A human specified it, an agent built it, a reviewer and a compute node took part, and the knowledge it used credits its creator. One hundred Contribution Units, split equally per contribution." |
| 10 | **Value Distribution** | 2:02–2:24 | STEP 5/9 + 7/9 + 8/9 | `/value/:id` payouts CONFIRMED → explorer of one payout → `/value/revenue/:id` (*simulated*, 20 / 5 / 75) → `/value/treasury` | "Each contributor is paid on Solana, one transfer per wallet, by units. When the operation earns revenue, here a simulated economic result, a fixed public policy shares it: twenty percent to contributors, five to the protocol, seventy-five retained." |
| 11 | **Reputation** | 2:24–2:32 | STEP 9/9 | `/value/identities/dev-agent-17` — *Reputation* (outcomes, units) | "Every identity builds a reputation: plain counts of accepted outcomes and units. No hidden score." |
| 12 | Close | 2:32–2:38 | — | end card: "CryptoBot — Proof of Value for Autonomous Agents" · repo · Apache-2.0 · devnet | "Units are attribution, not equity. CryptoBot: Proof of Value for Autonomous Agents." |

271 words of voice ≈ 108 s at 150 words per minute; the rest is air to read the screen. If the verdict of the take is
not ESCALATE (the tier depends on the USD value at the time of the take), shot 4 says: "Within the autonomous limit, it
passes all thirteen rules without a timelock."

## What the voice must not say

| Topic | True today | Do not say |
|---|---|---|
| Strategist / Guardian / Operator | Names of **pipeline roles**, not separate AI agents: the deterministic planner; the policy engine plus the independent validator; the approval plus the isolated signer (in the demo the script gives the approval with the operator role) | "an AI strategist decides", "a second agent reviewed it" |
| `ESCALATE · SECOND_AGENT` | The tier exists; its effect today is a **timelock plus the approval** | "a second agent approved" |
| The operation | A `SystemProgram.transfer` of SOL from the agent's wallet to **its own vault** — a rebalance, not a swap | "sell to USDC", "trade" |
| Revenue | 0.05 SOL, a **simulated** economic result (`simulated=true`) on top of a real operation; the payouts are real devnet transfers | "earned", "profit" |
| Payouts | Paid from the demo's **operating wallet**; the treasury is an accounting read model | "paid from the agent's treasury" |
| Contributions | **Declared** by the task record; economic causality is not proven | "we prove who caused the revenue" |
| Compute | Without `--compute-json` the compute receipt is **estimated** | compute costs as measured |
| Contribution Units | An attribution primitive — **not equity, not a token, not a promise of return** | "shares", "token", "yield" |
| Network | **Devnet** only | "mainnet", "live in production" |

## Before recording

- Stack: a **new** compose project with a clean database (`CRYPTOBOT_DEMO_PROJECT=<new name>`), never one that already
  recorded other tasks — `hackathon.sh --check --fresh` refuses a database that holds any ValueEvent.
- Wallet: the real operation moves 21–40 % of the demo wallet's SOL to the vault (a wallet of the demo), and the rest of a
  run spends ≈ 0.02 SOL (measured): fund the wallet before the take (`--min-sol` sets the floor the preflight enforces).
- Acceptance: the release-truth verdict must pass (JSON, exit 0); the run binds the artifact to the image it measured.
- Hygiene: neutral task ids only (the scripts refuse tracker ids); no lab hostnames, tokens, key paths or `?token=` URLs on
  screen.
