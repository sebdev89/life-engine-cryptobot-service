# CryptoBot demo — the real pipeline on Solana devnet

## One command, from zero, with a report (HK-7)

```bash
scripts/demo/run.sh                 # keys (once) → stack up → 4 acts → out/demo-report-<ts>.md → stack stopped
scripts/demo/run.sh --rpc local     # force the local solana-test-validator (unlimited airdrop, no explorer links)
scripts/demo/run.sh --target uat    # the same acts against a deployed service (.env.demo-uat, see the example)
scripts/demo/run.sh --dry-run       # print the plan, touch nothing
```

On a machine with Docker, curl, python3 and git that is all: `run.sh` generates the keys and
`.env.demo` if they are missing, picks devnet when the wallet holds ≥ 0.6 SOL there (otherwise the
local validator, and says so), builds and starts the stack, and runs the story in four acts —
each step printed with its evidence as it happens, each act timed:

| act | what happens | who runs it |
|---|---|---|
| 1 execute | request → plan → simulation → 13 rules + `R_v` + validator → 409 before approval → approval → timelock → execute (`Idempotency-Key`) → validator attests → signer signs → Solana → `SUBMITTED` → confirmed on chain → `EXECUTED` → replay = same tx → outbox → `EXECUTION` receipt verified → a mainnet intent → 409 | `e2e-devnet.sh` |
| 2 risk | an adversarial intent (dump 95 % of the position) → `BLOCKED_BY_POLICY` with the rules that failed (`MAX_TRADE_PCT_OF_PORTFOLIO`, `MAX_TRADE_USD`, `COOLDOWN`…), approve → 409, execute → 409, `RISK_DECISION` receipt verified; then the 60 s cooldown the wallet is under, waited out visibly; then (HK-4) a **second adversarial intent, wrong only in its price**: one oracle source is made to say −90 % (`PUT /api/cryptobot/demo/price {"asset":"SOL","source":"pyth-hermes","factor":0.1}`) → `BLOCKED_BY_POLICY` by **`PRICE_DEVIATION`** with the sources, the median and the limit in the message; then every source is made 15 min old (`{"source":"*","ageSeconds":900}`) → **`PRICE_STALE`**. Each block leaves the policy's **Decision Receipt** (`RISK_DECISION` by `policy-engine@R_v`, params `blockedBy`, `rule.<NAME>`, `oracle.SOL`) that is verified live — hash, signature and the engine re-run on the stored `(I, S)` (`reproduced=true`) — and appears in the proposal's lineage under the `STRATEGY` it validates; approve → 409; the injection is disarmed (`DELETE`) before act 3 | `run.sh` |
| 3 recovery | the RPC dies at broadcast → no verdict → dead letter → RPC back → `POST /dead-letters/{id}/requeue` (replay = 409) → idempotent retry (same `operationId`, new signature) → `EXECUTED`; the chain, asked directly: signature #1 never seen, #2 confirmed, vault +1 | `e2e-devnet.sh --chaos rpc-down` |
| 4 evidence | receipt DAG (parents of the `EXECUTION` receipt) → Merkle anchor of the receipts on Solana (`POST /anchors?wait=true`: memo tx signed by the signer, **finalized**) → inclusion proof of the `EXECUTION` receipt (`proofValid`) → batch verify (root recomputed, memo read back from the chain) → metrics | `run.sh` |

The report `out/demo-report-<ts>.md` has the act table (result, time, key facts), the evidence
tables of acts 1 and 3, the risk and anchor evidence, and the non-zero metrics; `out/run-<ts>/`
keeps the per-act evidence, the key=value summaries and the full log. Before it exits, `run.sh`
greps the report and the log for every secret value of `.env.demo` (exit 3 on a hit) and checks that
`.env.demo` and `out/` are gitignored. Exit 0 = every act passed; the acts that a target lacks by
design (chaos on UAT) are `SKIPPED` and say why. Budget: < 10 min after the first image build
(`run.sh` prints the total and the verdict). Three consecutive runs on the same stack need no
intervention: the wallet is topped up by airdrop on the local validator; on devnet each execution
moves ≈ 21 % of the position to the vault, so refill at https://faucet.solana.com when the balance
drops below 0.6 SOL (`--rpc auto` then falls back to the local validator and tells you).

`CRYPTOBOT_DEMO_PROJECT=<name>` names the compose project (containers, volumes, network): a new
name is a new stack — how "from zero" is proven without touching a previous rehearsal.

One command brings up **service + independent validator + isolated signer + Postgres**, runs the
whole control plane by API and leaves a Markdown file with the on-chain evidence:

```
intent → plan → simulation (exact bytes) → 13 rules + policy R_v (H_R) + validator identity
      → human approval → timelock → execute (Idempotency-Key)
      → executionPreconditions → mainnet gate → re-simulate → validator attests these bytes
      → signer signs (attestation required) → sendTransaction → SUBMITTED → confirmed → EXECUTED
      → EXECUTION receipt (signed, content-addressed) → same key again ⇒ same tx
      → a mainnet intent in the same run ⇒ 409
```

Nothing is stubbed: the validator and the signer are the real modules in their own containers,
the RPC is Solana's, and the signature is checked against the chain directly, not through the
service. Auth and Runtime are **not** needed (the JWT is minted with the demo's own secret; the
LLM advisor is optional and off the E2E path).

## Quick start (≈ 5 min the first time, < 2 min after)

```bash
# 1. keys + secrets + devnet SOL — once. Prints public keys only.
scripts/demo/wallet-devnet.sh            # ~/.cryptobot-demo/{demo-wallet,rebalance-vault,validator}.json + .env.demo
                                         # airdrops 2 SOL via RPC (rate-limited) — or fund the printed
                                         # wallet at https://faucet.solana.com and re-run

# 2. the demo (builds the images on the first run)
scripts/demo/e2e-devnet.sh               # stack up → flow → out/evidence-<ts>.md → stack stopped
scripts/demo/e2e-devnet.sh --keep        # leave it running (UI, curl, Postgres inspection)
scripts/demo/e2e-devnet.sh --it          # the Java test instead of curl (E2EDevnetIT, Failsafe)

# 3. Proof of Value, the whole story in 9 steps (stack kept up): docs/PROOF-OF-VALUE.md "End-to-end demo"
scripts/demo/pov-e2e.sh --task-id TASK-042 --acceptance-json <release-truth.json> [--skip-op]
```

The evidence file lists: wallet, proposal id, verdict + `H_R`, the 409 before approval, the
timelock 409, `operationId`, **transaction signature**, slot and confirmation as the RPC reports
them, the explorer link, the validator's attestation, the audit trail, the outbox events, the
`EXECUTION` receipt hash with its `verify` result, and the mainnet 409.

## The operator UI in the demo stack

The compose also builds and serves the operator UI (`cryptobot-ui`, the sibling repo, with its own
`Dockerfile`: Angular build → nginx) under the **`ui` profile**. It is a profile, not always-on, so
`run.sh` / `e2e-devnet.sh` and a checkout without the UI repo behave exactly as before.

```bash
# from the service checkout; the UI repo must sit next to it as ../cryptobot-ui:
#   git clone https://github.com/sebdev89/life-engine-cryptobot-ui ../cryptobot-ui
export CRYPTOBOT_DEMO_PROJECT=cryptobot-demo-main     # keep ONE project name: every new name creates a new Docker network
docker compose -p cryptobot-demo-main -f docker-compose.demo.yml --env-file .env.demo --profile ui up -d --build
scripts/demo/ui-url.sh                                # → http://127.0.0.1:4204/live?token=<1 h demo JWT>
```

Open the printed URL: the UI keeps the token (localStorage) and `/live` shows the demo path —
timeline, approve/execute, chaos panel (visible because the demo enables it), dead letters.

| knob | default | what |
|---|---|---|
| `UI_PORT` | `4204` | host port of the UI (`127.0.0.1` only). The service CORS is built from the **same** variable, so changing it keeps both in step. |
| `CRYPTOBOT_UI_CONTEXT` | `../cryptobot-ui` | build context of the UI. From a worktree outside `active/cryptobot/`, set it to the absolute path of the UI checkout (e.g. when it was cloned as `life-engine-cryptobot-ui`). |
| `UI_DEMO_CLUSTER` | `devnet` | label + explorer links in the UI; `local` when the stack runs with `--profile local-validator`. |
| `CRYPTOBOT_DEMO_PORT` | `8091` | the service's published port; `config.js` points the browser at `http://127.0.0.1:<it>`. |

Notes:

- The **browser** calls the API, so `config.js` holds the host URL of the service (`http://127.0.0.1:8091`),
  not the compose-internal name. Check it with `curl -s http://127.0.0.1:4204/config.js`.
- No Auth in the stack: `ui-url.sh` mints the token with the `JWT_SECRET` of `.env.demo` (the same
  `jwt_hs256` the scripts use) for a fixed demo operator; it never prints the secret. Re-run it after 1 h.
- Port 4204 busy (`address already in use`)? Something else listens there (`ss -ltnp | grep 4204`):
  stop it, or use `UI_PORT=4214` on **both** the `up` and `ui-url.sh`.
- To leave the demo, `docker compose -p cryptobot-demo-main … --profile ui stop` (not `down`: `down`
  recreates the network on the next `up`).
- `run.sh` with `--keep` plus a later `--profile ui up -d cryptobot-ui` puts the UI on top of a
  rehearsal that is still running.

## Timings for the video

Every wait of the demo is a compose variable with the rehearsed value as default; put the short
values in `.env.demo` (or a copy passed with `--env-file`) — `run.sh` and `e2e-devnet.sh` source
the same file, so the service and the scripts agree on how long to wait:

```bash
CRYPTOBOT_TIMELOCK_ESCALATED=10s        # default 20s — only an ESCALATE verdict has a timelock
CRYPTOBOT_POLICY_COOLDOWN=15s           # default 60s — rule COOLDOWN; not in H_R, no re-pin needed
CRYPTOBOT_RECONCILIATION_INTERVAL=5s    # default 10s
CRYPTOBOT_RECONCILIATION_GRACE=10s      # default 20s
# CRYPTOBOT_RECONCILIATION_MAX_ATTEMPTS stays 3 ("three attempts, no verdict: dead letter")
```

Measured 2026-09-29, `run.sh --rpc local --target local`, project `cryptobot-demo-main`:

| act | defaults | video values |
|---|---|---|
| 0 setup | 12s | 12s |
| 1 execute (scene A) | 8s | 8s |
| 2 risk (cooldown inside) | 62s | 18s |
| 3 recovery rpc-down (scene B) | 94s | 65s |
| 4 evidence | 15s | 15s |
| total | 3m21s | 2m08s |

On the local validator act 1 is `ALLOW` (no timelock); a larger devnet position can be `ESCALATE`,
which adds `CRYPTOBOT_TIMELOCK_ESCALATED` to scene A. Always the project `cryptobot-demo-main`, and
one run at a time: two sessions on the same project recreate each other's service mid-run.

## Values for the video

The wallet, the label and the sell size are what appears on screen; none of them needs a code change.

- **Wallet.** `/demo` (UI) reads `UI_DEMO_WALLET`; the compose passes `DEMO_WALLET_ADDRESS` from `.env.demo`
  to the `cryptobot-ui` service, so `/demo` does not ask for it by hand. Needs the UI image with the
  newer entrypoint (`demoWallet` from `UI_DEMO_WALLET`); an older image ignores the variable.
  Unset in `.env.demo` → the UI gets an empty value and falls back to asking once.
- **Label.** The wallet is registered with a default demo label. For the recording: `DEMO_WALLET_LABEL="Treasury demo"`
  in the environment of `run.sh` / `e2e-devnet.sh` (default unchanged).
- **Where the `SELL … SOL → 79 %` comes from.** `run.sh` sells `--sell-sol` (default `0.5`), but
  `e2e-devnet.sh` clamps the sale to 21–40 % of the SOL held, and the target is
  `int(weight × (1 − sell/amount))`. On a wallet with ≈ 5 SOL, 0.5 SOL is below the 21 % floor, so the
  sale is 21 % → `SOL → 79 %` (100 × 0.79). Not a bug, but not a round number either.
- **Recommended for the video:** wallet with ≈ 5 SOL and **`--sell-sol 1.5`** (30 % of the position,
  inside the 21–40 % band and under the 2 SOL/tx cap) → `SOL → 70 %`, a round figure that is also
  under the `R_v` 80 % concentration limit. On 5 SOL: 1.5 ≤ 2 SOL cap. Do not lower the cap or the
  band for looks: they are the rules the demo is showing.
- **`/demo` (UI, scene A/B) has its own size**: the runner sells 0.5 SOL clamped to 21–40 % like the
  script, so on a ≈ 5 SOL wallet it also shows `SOL → 79 %`. That constant lives in the UI repo
  (out of this repo's scope); measure what the screen shows in the rehearsal and, if 79 % is unwanted, propose it there.
- The adversarial intents (`SOL → 5 %`, `SOL → 50 %`, BONK mint) are fixed on purpose: 5 % is the
  "dump 95 %" the policy must refuse.

## Recovery, visible (HK-3): `--chaos <mode>`

The demo compose enables **fault injection** (`CRYPTOBOT_CHAOS_ENABLED=true`, demo only — UAT/PROD
never set it, and without it the endpoint and the faulty client do not exist). The script arms a
fault right before the broadcast and then watches the system recover:

```bash
scripts/demo/e2e-devnet.sh --local-validator --chaos rpc-down          # the full loop, ≈ 3 min
scripts/demo/e2e-devnet.sh --local-validator --chaos uncertain
scripts/demo/e2e-devnet.sh --local-validator --chaos confirm-timeout
scripts/demo/e2e-devnet.sh --local-validator --it                      # E2EDevnetIT incl. the rpc-down test
```

| mode | what breaks | what the evidence shows |
|---|---|---|
| `uncertain` | `sendTransaction` is performed, the RPC answer is lost | `EXECUTION_BROADCAST_UNCERTAIN`, row `EXECUTING`+`SIGNED` with the signature persisted **before** the broadcast → the reconciler finds it confirmed → `RECONCILED` → `EXECUTED`, same signature, no retry |
| `confirm-timeout` | broadcast ok, the confirmation poll fails | `SUBMITTED` → reconciler → `EXECUTED` |
| `rpc-down` | nothing is sent; status/height calls fail until disarmed | uncertain row → no verdict × 3 attempts → **dead letter** `ambiguous` (`GET /api/cryptobot/dead-letters`, `cryptobot_dead_letter_open=1`) → RPC back → `POST /dead-letters/{id}/requeue` (one-shot: replay = 409) → blockhash expired unseen → **idempotent retry**: `EXECUTION_RETRIED`, same `operationId`, **new signature** → `EXECUTED`. Then the chain is asked directly: signature #1 `never-seen`, signature #2 `confirmed`, vault transfers **+1** |

Every line lands in `out/evidence-<ts>.md`. Knobs (demo only): `CRYPTOBOT_RECONCILIATION_INTERVAL`
(10s), `_GRACE` (20s), `_MAX_ATTEMPTS` (3), `_MAX_RETRIES` (2). The chaos endpoint:
`GET|PUT|DELETE /api/cryptobot/demo/chaos` (`RUNTIME_ADMIN`), body `{"broadcast":"rpc-down","shots":-1}`.
Runbook for the human side: `docs/runbooks/dead-letter.md`.

## Risk, visible (HK-4): the adversarial price

The same demo profile enables the **price injection** (`/api/cryptobot/demo/price`, `RUNTIME_ADMIN`;
or `CRYPTOBOT_DEMO_PRICE_OVERRIDE=SOL:pyth-hermes:factor=0.1` at startup). It tampers with what the
oracle's sources *said* — after they answered, before the real `PriceOracle` reduces them — so the
refusal is the oracle's own: quorum, freshness, deviation, breaker, drift. Nothing is bypassed and
every injection is logged (`oracle_price_injected — DEMO ONLY`, and `GET /demo/price` lists them).

```bash
PUT /api/cryptobot/demo/price {"asset":"SOL","source":"pyth-hermes","factor":0.1}   # one source −90 %  ⇒ PRICE_DEVIATION
PUT /api/cryptobot/demo/price {"asset":"SOL","source":"*","ageSeconds":900}          # every source stale ⇒ PRICE_STALE
PUT /api/cryptobot/demo/price {"asset":"SOL","source":"*","factor":0.1}              # the market "crashes" ⇒ PRICE_CIRCUIT_BREAKER (+ PRICE_DRIFT vs the plan)
DELETE /api/cryptobot/demo/price                                                     # disarm (run.sh always does, even on failure)
```

`source` is a source id (`jupiter-price-v3`, `pyth-hermes`, `coingecko-simple`) or `*`; a source
that did not answer gets an observation invented from the median of the others, so the demo does
not depend on which real feeds are up. Only the demo compose sets `CRYPTOBOT_CHAOS_ENABLED`; on
`--target uat` the price intents are `SKIPPED` and the report says why.

## When devnet does not cooperate (faucet dry, RPC slow): plan B

Same stack, same bytes, against a local `solana-test-validator` (Anza image) with unlimited airdrop:

```bash
scripts/demo/e2e-devnet.sh --local-validator [--keep] [--it]
```

The service still labels the cluster `devnet` (the signer only signs for devnet), so the explorer
link does not resolve — the evidence file says `mode: local-validator`. Use it to rehearse; use
devnet for the recording. The devnet RPC airdrop allows a few SOL per day per IP; when it says
"limit reached", the web faucet (GitHub login) is the way.

## What is where

| File | Role |
|---|---|
| `docker-compose.demo.yml` | the stack: `demo-postgres`, `cryptobot-validator` (:8097, internal), `cryptobot-signer` (:8096, internal), `cryptobot-service` (127.0.0.1:8091), optional `solana-local` (profile `local-validator`, 127.0.0.1:8999) |
| `.env.demo` (gitignored) | generated secrets: `JWT_SECRET`, signer/validator tokens, DB password, receipt signing key, the public keys, the key file paths, `VALIDATOR_POLICY_HASH` pin, demo knobs |
| `~/.cryptobot-demo/*.json` (0600) | the wallet the signer controls, the rebalance vault (destination), the validator's attestation key. Mounted read-only; the containers run as your uid to read them |
| `scripts/demo/wallet-devnet.sh` | generates keys (`solana-keygen` if installed, else python `cryptography`/openssl) and `.env.demo`; airdrops |
| `scripts/demo/e2e-devnet.sh` | the flow by curl + evidence; `--chaos <mode>` injects a failure and shows the recovery; `--it` runs `E2EDevnetIT` |
| `scripts/demo/lib.sh` | helpers: JSON-RPC, base58 pubkey of a keypair, HS256 token, default `H_R` |
| `src/test/java/io/lifeengine/cryptobot/e2e/E2EDevnetIT.java` | the same flow as assertions; `./mvnw -Pe2e-devnet verify` with the stack up |

## Knobs (all in `.env.demo`, local only — never copy them to UAT/PROD)

| Variable | Default | Why |
|---|---|---|
| `CRYPTOBOT_TIMELOCK_ESCALATED` | `20s` | so an ESCALATE verdict shows the timelock (409 + wait) without the 30 min of a real environment |
| `CRYPTOBOT_RECONCILIATION_INTERVAL` / `_GRACE` / `_MAX_ATTEMPTS` / `_MAX_RETRIES` | `10s` / `20s` / `3` / `2` | so the whole recovery loop (no verdict → DLQ → requeue → retry) fits in ≈ 3 min; production is 30s / 2m / 20 / 2 |
| `CRYPTOBOT_CHAOS_ENABLED` | `true` (compose) | fault injection and the adversarial price injection (`/api/cryptobot/demo/price`); **never** in UAT/PROD |
| `CRYPTOBOT_DEMO_PORT` | `8091` | host port of the service |
| `CRYPTOBOT_SOLANA_DEVNET_RPC` | `https://api.devnet.solana.com` | any devnet RPC; `--local-validator` overrides it |
| `--sell-sol N` (script) / `CRYPTOBOT_E2E_SELL_SOL` (IT) | `1` | size of the SELL leg, clamped to 21–40 % of the SOL held so every rule holds: `R_v` `ASSET_CONCENTRATION` (SOL ≤ 80 % after), ≤ $500, ≤ 50 % of the portfolio, ≤ 2 SOL per tx. Keep the wallet between 0.5 and 9 SOL |

Everything else is the production configuration: `CRYPTOBOT_EXECUTION_ENABLED=true`,
`CRYPTOBOT_ALLOW_MAINNET=false`, `SIGNER_REQUIRE_ATTESTATION=true`, `SIGNER_ALLOW_MAINNET=false`,
validator pinned by `H_R`.

## What the first real run found (and fixed)

The pipeline had never run with the real signer and validator — `ControlPlaneFlowTest` stops at
`execute = 409` and the execution tests mock both. Three things broke the moment they ran together:

1. `signer/` and `validator/` were packaged as plain jars (no `repackage`): their containers died
   at boot with `no main manifest attribute`. The hackathon compose never worked as written.
2. The key files are `0600` on the host; the images run as uid 10002/10003 → `AccessDeniedException`
   on the keypair. The demo compose runs those two containers as the host user (`DEMO_UID`).
3. The signer/validator images had no `wget`/`curl`, so a compose healthcheck could not exist;
   they are alpine now, like the service image, with a `HEALTHCHECK`.

Nothing in the pipeline itself needed a change: preconditions, mainnet gate, validator
attestation, signer refusal rules, signature verification, broadcast, confirmation, receipts and
idempotency behaved as specified on the first complete run.

## Cleaning up

```bash
docker compose -f docker-compose.demo.yml --env-file .env.demo --profile local-validator down -v
rm -rf out/            # evidence files (gitignored)
# keys stay in ~/.cryptobot-demo; delete them yourself if you want a fresh wallet
```
