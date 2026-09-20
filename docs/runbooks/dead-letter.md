# Runbook — CryptoBot dead-letter queue (DLQ)

**Alert:** `cryptobot_dead_letter_open > 0` (also `dlq_size > 0`, the KAN-403 name) for more than
5 minutes. **Severity:** P1 while a `RECONCILIATION` letter is open — a trade is in flight and the
system has stopped deciding about it. **Owner:** CryptoBot on-call (verticals). Issues: KAN-501 (this
runbook and the API), KAN-571 (idempotent retry, fault injection, metrics).

A dead letter is *something the system refuses to guess about*. It is never dropped; a human
closes it through the API below, and every decision leaves an `audit_event`. There are two sources:

| `source` | `payload.kind` | What happened | Default action |
|---|---|---|---|
| `RECONCILIATION` | `ambiguous` | The reconciler asked the chain `max-attempts` times (20 in prod, ≈ every 2 min) and never got a verdict: the RPC was down, or the signature was never seen while its blockhash was still valid. The proposal is left **in flight** (`EXECUTING`/`SUBMITTED`) with its signature. | Check the RPC; **requeue** |
| `RECONCILIATION` | `retries_exhausted` | The signature was never seen and its blockhash expired; the operation was retried idempotently `max-retries` times (2 in prod) and every retry was lost the same way. Nothing is on the chain for this `operationId`. | Find out why broadcasts are lost; **resolve** (⇒ `FAILED`) or **requeue** for one more round |
| `RECONCILIATION` | `inconsistent` | `SUBMITTED` without a signature: the row contradicts itself (a bug, or a hand edit). | Investigate the row; **resolve** |
| `OUTBOX` | `outbox` | An outbox event failed delivery 8 times (the sink was down). The event row is `FAILED`. The proposal itself is fine. | Fix the sink; **requeue** |

## 1. Triage (2 minutes)

```bash
TOKEN=…   # a JWT with RUNTIME_ADMIN (the DLQ is an admin surface; operators get 403)
BASE=https://<cryptobot-service>          # demo: http://127.0.0.1:8091
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/cryptobot/dead-letters?resolved=false" | jq
```

Each letter shows `id`, `source`, `payload.kind`, `reason`, `proposalId`, `payload.signature`,
`payload.previousSignature`, `payload.retries`, `payload.operationId`, `createdAt`. Filters:
`?source=RECONCILIATION|OUTBOX`, `?proposalId=…`, `?resolved=true|false|all`, `?limit=&offset=`.
`open` in the response is the global unresolved count (= `cryptobot_dead_letter_open`).

For a `RECONCILIATION` letter, read the proposal and its trail (the owner's token, or the DB):

```bash
curl -s -H "Authorization: Bearer $OWNER_TOKEN" "$BASE/api/cryptobot/proposals/<proposalId>" | jq '.proposal.status, .proposal.operationId, .proposal.execution, [.audit[].eventType]'
```

Look for `EXECUTION_BROADCAST_UNCERTAIN` (the RPC answer was lost), `EXECUTION_CONFIRMATION_PENDING`
(polling gave up), `RECONCILIATION_AMBIGUOUS` / `RECONCILIATION_RETRIES_EXHAUSTED`, and any
`EXECUTION_RETRIED` (each one names `previousSignature` → `signature` under the same `operationId`).

## 2. Verify on the chain yourself (never trust the row)

```bash
RPC=https://api.devnet.solana.com        # devnet; the demo's local validator: http://127.0.0.1:8999
for SIG in <payload.signature> <payload.previousSignature>; do
  curl -s "$RPC" -X POST -H 'content-type: application/json' \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getSignatureStatuses\",\"params\":[[\"$SIG\"],{\"searchTransactionHistory\":true}]}" | jq '.result.value[0]'
done
curl -s "$RPC" -X POST -H 'content-type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"getBlockHeight","params":[{"commitment":"confirmed"}]}' | jq .result
```

| The chain says | Meaning | Action |
|---|---|---|
| `confirmationStatus: confirmed\|finalized`, `err: null` | The trade landed. | **resolve** — the service re-checks and closes the proposal `EXECUTED` |
| `err: {...}` | The node executed it and it failed. | **resolve** — closes `FAILED` with the on-chain error |
| `null` and block height **>** `execution.lastValidBlockHeight` | Never included, can never be: the bytes are dead. | **requeue** if the operation should still happen (idempotent retry, new signature, same `operationId`); **resolve** to abandon (⇒ `FAILED`) |
| `null` and block height **≤** `lastValidBlockHeight` | Still inside the window (~60–90 s). | Wait; `resolve` is refused with 409 until there is a verdict |
| RPC unreachable | No verdict possible. | Fix/replace the RPC (`CRYPTOBOT_SOLANA_DEVNET_RPC`), then **requeue** |

Explorer: `https://explorer.solana.com/tx/<signature>?cluster=devnet`.

## 3. Resolve — "I looked, close it"

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"note":"explorer: signature finalized in slot 123456"}' "$BASE/api/cryptobot/dead-letters/<id>/resolve" | jq
```

What it does, in order: for a `RECONCILIATION` letter the proposal is **settled against the chain
once more** (`ReconciliationService.settle`, ignoring the attempt ceiling): confirmed ⇒ `EXECUTED`,
on-chain error ⇒ `FAILED`, never seen and expired ⇒ `FAILED` with your note (no retry), no verdict ⇒
**409** and the letter stays open. Then `resolved_at`, `resolved_by` (your JWT subject/email),
`resolution` (the note) and `outcome=RESOLVED` are written on the letter, `DEAD_LETTER_RESOLVED`
is appended to the proposal's audit, `cryptobot_dead_letter_total{reason="resolved"}` +1 and
`cryptobot_dead_letter_open` −1. For an `OUTBOX` letter only the letter is closed; the event stays
`FAILED`. A resolved letter cannot be resolved or requeued again (409).

The response carries `deadLetter`, `proposal` (its new status) and `reconciliation`
(`CORRECTED` when the proposal moved).

## 4. Requeue — "let the system try again"

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"note":"RPC restored 10:42, retrying"}' "$BASE/api/cryptobot/dead-letters/<id>/requeue" | jq
```

- `OUTBOX`: the event goes back to `PENDING` with `attempts=0`, due now; the publisher delivers it on
  its next tick (2 s). Response: `event`.
- `RECONCILIATION`: the proposal's `reconciliationAttempts` is reset to 0 (audit
  `DEAD_LETTER_REQUEUED` in the same commit), the letter is closed with `outcome=REQUEUED`, and the
  proposal is **reconciled immediately**. Response `reconciliation`:
  - `CORRECTED` — the chain had a verdict; the proposal is `EXECUTED`/`FAILED`.
  - `RETRIED` — the signature was never seen and its blockhash had expired: the operation ran again
    end to end (fresh blockhash, re-simulation, validator attestation, signer, broadcast) under the
    **same** `operationId`. `execution.signature` is new, `execution.previousSignature` is the dead
    one, `execution.retries` +1, audit `EXECUTION_RETRIED`. The request waits for the confirmation
    poll (up to ~30 s).
  - `MATCHED` — still no verdict (inside the window, or the RPC is still down): the row is back in
    the periodic sweep; a new letter will be written if it stays ambiguous.
  - `DEAD_LETTERED` — retries are exhausted; a new `retries_exhausted` letter exists.

**Why a requeue can never double-execute.** The retry is only taken when the chain has proven the
previous bytes can no longer be included (never seen **and** block height past
`lastValidBlockHeight`); the new signature is bound to the same `operationId` (unique index
`uq_action_proposal_operation`); the first commit of the retry is guarded by the proposal's
`version`, so a concurrent live request or second reconciler loses with `StaleProposal`; and the
letter itself is one-shot (`UPDATE … WHERE resolved_at IS NULL`), so a double click is a 409.
`max-retries` (2) bounds the loop. The demo proves it on a real chain
(`scripts/demo/e2e-devnet.sh --chaos rpc-down`): signature #1 never on chain, signature #2
confirmed, exactly one transfer into the vault.

## 5. Who authorises

- **resolve** of a `RECONCILIATION` letter and any **requeue**: the CryptoBot owner (Sebastián) or
  the on-call with a `RUNTIME_ADMIN` token; the note must say what was checked (explorer link or
  `getSignatureStatuses` output). Amounts are bounded by the policy caps either way
  (`max-lamports-per-tx`, `max-trade-usd`, signer caps).
- **resolve** of an `OUTBOX` letter: on-call, no approval.
- Never edit `action_proposal` / `dead_letter` by hand; the API keeps the audit trail and the
  metrics consistent.

## 6. Metrics and where to look

| Series | Meaning |
|---|---|
| `cryptobot_dead_letter_open` (gauge, = `dlq_size`) | unresolved letters — the alert |
| `cryptobot_dead_letter_total{reason}` | `ambiguous` · `retries_exhausted` · `inconsistent` · `outbox` created; `resolved` · `requeued` closed |
| `cryptobot_reconciliation_total{outcome}` | per row per sweep: `matched` · `corrected` · `retried` · `dead_lettered` · `skipped` |
| `trade_reconciled_total{result}`, `reconciliation_mismatch_total` | the KAN-403 series, unchanged |

Logs (service): `reconciliation_dead_letter`, `reconciliation_retry` / `reconciliation_retried`,
`dead_letter_resolved` / `dead_letter_requeued`, `execution_retry`, `proposal_broadcast_uncertain`.

Knobs (`application.yml` → env): `CRYPTOBOT_RECONCILIATION_INTERVAL` (30s), `_GRACE` (2m),
`_MAX_ATTEMPTS` (20), `_MAX_RETRIES` (2). The demo compose shortens them (10s / 20s / 3 / 2) so the
whole loop is visible in about three minutes.

## 7. Rehearsal

Local, with a real chain (local validator or devnet), in one command:

```bash
scripts/demo/e2e-devnet.sh --local-validator --chaos rpc-down     # → out/evidence-<ts>.md
scripts/demo/e2e-devnet.sh --local-validator --chaos uncertain    # reconciler closes it, no retry
scripts/demo/e2e-devnet.sh --local-validator --chaos confirm-timeout
```

UAT (KAN-574/575): the same script against the UAT base URL once the signer/validator run there;
the chaos endpoint does **not** exist outside the demo compose (`CRYPTOBOT_CHAOS_ENABLED` unset), so
in UAT the rehearsal is: stop the RPC (or point `CRYPTOBOT_SOLANA_DEVNET_RPC` at a closed port),
execute, restore, requeue. Paste the evidence file into the issue.
