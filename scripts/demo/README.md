# CryptoBot demo — the real pipeline on Solana devnet (KAN-570)

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
```

The evidence file lists: wallet, proposal id, verdict + `H_R`, the 409 before approval, the
timelock 409, `operationId`, **transaction signature**, slot and confirmation as the RPC reports
them, the explorer link, the validator's attestation, the audit trail, the outbox events, the
`EXECUTION` receipt hash with its `verify` result, and the mainnet 409.

## Recovery, visible (KAN-571 / HK-3): `--chaos <mode>`

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
| `scripts/demo/e2e-devnet.sh` | the flow by curl + evidence; `--chaos <mode>` injects a failure and shows the recovery (KAN-571); `--it` runs `E2EDevnetIT` |
| `scripts/demo/lib.sh` | helpers: JSON-RPC, base58 pubkey of a keypair, HS256 token, default `H_R` |
| `src/test/java/io/lifeengine/cryptobot/e2e/E2EDevnetIT.java` | the same flow as assertions; `./mvnw -Pe2e-devnet verify` with the stack up |

## Knobs (all in `.env.demo`, local only — never copy them to UAT/PROD)

| Variable | Default | Why |
|---|---|---|
| `CRYPTOBOT_TIMELOCK_ESCALATED` | `20s` | so an ESCALATE verdict shows the timelock (409 + wait) without the 30 min of a real environment |
| `CRYPTOBOT_RECONCILIATION_INTERVAL` / `_GRACE` / `_MAX_ATTEMPTS` / `_MAX_RETRIES` | `10s` / `20s` / `3` / `2` | so the whole recovery loop (no verdict → DLQ → requeue → retry) fits in ≈ 3 min; production is 30s / 2m / 20 / 2 |
| `CRYPTOBOT_CHAOS_ENABLED` | `true` (compose) | KAN-571 fault injection; **never** in UAT/PROD |
| `CRYPTOBOT_DEMO_PORT` | `8091` | host port of the service |
| `CRYPTOBOT_SOLANA_DEVNET_RPC` | `https://api.devnet.solana.com` | any devnet RPC; `--local-validator` overrides it |
| `--sell-sol N` (script) / `CRYPTOBOT_E2E_SELL_SOL` (IT) | `1` | size of the SELL leg, clamped to 21–40 % of the SOL held so every rule holds: `R_v` `ASSET_CONCENTRATION` (SOL ≤ 80 % after), ≤ $500, ≤ 50 % of the portfolio, ≤ 2 SOL per tx. Keep the wallet between 0.5 and 9 SOL |

Everything else is the production configuration: `CRYPTOBOT_EXECUTION_ENABLED=true`,
`CRYPTOBOT_ALLOW_MAINNET=false`, `SIGNER_REQUIRE_ATTESTATION=true`, `SIGNER_ALLOW_MAINNET=false`,
validator pinned by `H_R`.

## What the first real run found (and fixed, KAN-570)

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
