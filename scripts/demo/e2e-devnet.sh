#!/usr/bin/env bash
# KAN-570 — the demo, end to end, by API, with evidence.
#
#   scripts/demo/e2e-devnet.sh [--local-validator] [--no-up] [--keep] [--sell-sol 1] [--it]
#
# 1. brings the demo stack up (docker-compose.demo.yml + .env.demo; --local-validator adds the
#    solana-test-validator profile and points the service at it), waits for health;
# 2. makes sure the wallet has SOL (airdrop on the local validator; on devnet, what
#    wallet-devnet.sh left there);
# 3. runs the flow with curl: register wallet → rebalance intent → simulation + policy →
#    execute-before-approval (409) → approve → timelock (409 while it runs) → execute with an
#    Idempotency-Key → SUBMITTED → EXECUTED → same key again (no second tx) → audit → outbox →
#    EXECUTION receipt + verify → a mainnet intent in the same run (409, fail-closed);
# 4. asks the RPC directly for the signature (getSignatureStatuses) — the chain, not the service;
# 5. writes out/evidence-<ts>.md (proposal id, operationId, signature, explorer link, receipt hash…).
#
# --it runs E2EDevnetIT (Failsafe, profile e2e-devnet) against the same stack instead of curl.
# --no-up assumes the stack is already up. --keep leaves it running at the end (default: stop).
# Never prints a secret: the token is minted in memory from JWT_SECRET, the keys are never read.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "${HERE}/../.." && pwd)"
# shellcheck disable=SC1091
source "${HERE}/lib.sh"

LOCAL_VALIDATOR=0; UP=1; KEEP=0; RUN_IT=0; SELL_SOL=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --local-validator) LOCAL_VALIDATOR=1; shift ;;
    --no-up) UP=0; shift ;;
    --keep) KEEP=1; shift ;;
    --it) RUN_IT=1; shift ;;
    --sell-sol) SELL_SOL="$2"; shift 2 ;;
    -h|--help) sed -n 2,20p "$0"; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
done

need docker; need curl; need python3
ENV_FILE="${PROJECT}/.env.demo"
[[ -f "$ENV_FILE" ]] || fail "no ${ENV_FILE}: run scripts/demo/wallet-devnet.sh first"
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

COMPOSE=(docker compose -f "${PROJECT}/docker-compose.demo.yml" --env-file "$ENV_FILE")
if [[ "$LOCAL_VALIDATOR" -eq 1 ]]; then
  COMPOSE+=(--profile local-validator)
  export CRYPTOBOT_SOLANA_DEVNET_RPC="http://solana-local:8899"
  HOST_RPC="http://127.0.0.1:${SOLANA_LOCAL_PORT:-8999}"
  MODE="local-validator (solana-test-validator in Docker; explorer links do not resolve)"
else
  HOST_RPC="${CRYPTOBOT_SOLANA_DEVNET_RPC:-https://api.devnet.solana.com}"
  MODE="devnet (${HOST_RPC})"
fi
BASE="http://127.0.0.1:${CRYPTOBOT_DEMO_PORT:-8091}"
WALLET="${DEMO_WALLET_ADDRESS:?}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="${PROJECT}/out"; mkdir -p "$OUT_DIR"
EVIDENCE="${OUT_DIR}/evidence-${TS}.md"

cleanup() {
  if [[ "$KEEP" -eq 0 && "$UP" -eq 1 ]]; then
    log "stopping the demo stack (--keep to leave it running)"
    "${COMPOSE[@]}" stop >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# ---- 1. stack ---------------------------------------------------------------------------------
if [[ "$UP" -eq 1 ]]; then
  log "mode: ${MODE}"
  log "docker compose up (build on first run: a few minutes)"
  "${COMPOSE[@]}" up -d --build
fi
log "waiting for ${BASE}/api/cryptobot/health"
for i in $(seq 1 90); do
  if curl -fsS -m 3 "${BASE}/api/cryptobot/health" 2>/dev/null | grep -q '"UP"'; then break; fi
  sleep 2
  [[ "$i" -eq 90 ]] && { "${COMPOSE[@]}" logs --tail 50 cryptobot-service >&2; fail "service not UP"; }
done
log "service UP"

# ---- 2. SOL -----------------------------------------------------------------------------------
bal="$(balance_lamports "$HOST_RPC" "$WALLET")"
# 3 SOL: enough for a SELL leg that satisfies every cap (≥ 20 % of the position so SOL ends ≤ 80 %,
# ≤ 2 SOL per tx, ≤ $500, ≤ 50 % of the portfolio) and for the fees. A wallet over ~9 SOL cannot
# satisfy "≥ 20 %" and "≤ 2 SOL" at once: keep the demo wallet small.
if [[ "$LOCAL_VALIDATOR" -eq 1 ]] && (( bal < 1000000000 )); then
  log "airdropping 3 SOL on the local validator"
  airdrop_confirmed "$HOST_RPC" "$WALLET" 3000000000 || warn "airdrop not confirmed yet"
  bal="$(balance_lamports "$HOST_RPC" "$WALLET")"
fi
(( bal > 100000000 )) || fail "wallet ${WALLET} holds ${bal} lamports on ${HOST_RPC}: fund it (scripts/demo/wallet-devnet.sh or https://faucet.solana.com) or use --local-validator"
log "wallet ${WALLET}: ${bal} lamports"

# ---- --it: the Java integration test instead of curl -------------------------------------------
if [[ "$RUN_IT" -eq 1 ]]; then
  log "running E2EDevnetIT (Failsafe, profile e2e-devnet)"
  ( cd "$PROJECT" && CRYPTOBOT_E2E_RPC_URL="$HOST_RPC" CRYPTOBOT_E2E_EVIDENCE="${OUT_DIR}/evidence-${TS}-it.txt" \
      MAVEN_OPTS="${MAVEN_OPTS:--Xmx1g}" ./mvnw -q -B -Pe2e-devnet verify )
  log "evidence: ${OUT_DIR}/evidence-${TS}-it.txt"
  exit 0
fi

# ---- 3. the flow, by API ------------------------------------------------------------------------
TOKEN="$(jwt_hs256 "$JWT_SECRET" "$(python3 -c 'import uuid; print(uuid.uuid4())')" "demo@cryptobot.local")"
api() { # api <method> <path> [json-body] [extra curl args...]
  local m="$1" p="$2" b="${3:-}"; shift 3 || shift $#
  if [[ -n "$b" ]]; then
    curl -sS -m 150 -X "$m" "${BASE}${p}" -H "Authorization: Bearer ${TOKEN}" -H 'content-type: application/json' -d "$b" -w '\n%{http_code}' "$@"
  else
    curl -sS -m 150 -X "$m" "${BASE}${p}" -H "Authorization: Bearer ${TOKEN}" -w '\n%{http_code}' "$@"
  fi
}
jget() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval('d'+sys.argv[1]))" "$1" 2>/dev/null || true; }
split_status() { STATUS="${RESP##*$'\n'}"; BODY="${RESP%$'\n'*}"; }
step() { printf '\n\033[1;32m== %s\033[0m\n' "$*" >&2; }
EV=() ; ev() { EV+=("$*"); }

step "0. register the devnet wallet the signer controls"
RESP="$(api POST /api/cryptobot/wallets "{\"address\":\"${WALLET}\",\"cluster\":\"devnet\",\"label\":\"KAN-570 demo\"}")"; split_status
[[ "$STATUS" == "201" ]] || fail "register: HTTP ${STATUS} ${BODY}"
WALLET_ID="$(printf '%s' "$BODY" | jget "['wallet']['id']")"
TOTAL_USD="$(printf '%s' "$BODY" | jget "['snapshot']['totalUsd']")"
RISK="$(printf '%s' "$BODY" | jget "['risk']['overall']")"
log "wallet ${WALLET_ID}: total \$${TOTAL_USD}, risk ${RISK}"
ev "wallet | \`${WALLET}\` (id ${WALLET_ID}), portfolio \$${TOTAL_USD}, risk ${RISK}"
# The SELL leg is what gets executed: sell --sell-sol SOL, clamped to [21 %, 40 %] of the SOL held so
# every rule holds whatever the balance: R_v ASSET_CONCENTRATION (SOL ≤ 80 % after), ≤ $500,
# ≤ 50 % of the portfolio, ≤ 2 SOL per tx. target weight = weight × (1 − sell/amount).
TARGET_PCT="$(printf '%s' "$BODY" | python3 -c "
import json, sys
d = json.load(sys.stdin); sell = float(sys.argv[1])
sol = [p for p in d['snapshot']['positions'] if p['symbol'] == 'SOL' and p.get('priceUsd') is not None][0]
amount, weight = float(sol['amount']), float(sol['weightPct'])
sell = max(0.21 * amount, min(sell, 0.4 * amount))
if sell > 2.0: sys.exit('wallet holds %.2f SOL: 21 %% of it is over the 2 SOL cap; keep the demo wallet between 0.5 and 9 SOL' % amount)
print(int(weight * (1 - sell / amount)))
" "$SELL_SOL")" || fail "$TARGET_PCT"
log "target: SOL → ${TARGET_PCT}% (sell ≈ ${SELL_SOL} SOL, clamped to 21–40 % of the position)"

step "1-3. intent SOL → ${TARGET_PCT}% : plan → simulation on the exact bytes → 13 rules + R_v + validator"
RESP="$(api POST "/api/cryptobot/wallets/${WALLET_ID}/proposals" "{\"kind\":\"REBALANCE\",\"targetWeights\":{\"SOL\":${TARGET_PCT}},\"reasoningSummary\":\"demo: reduce SOL concentration\"}")"; split_status
[[ "$STATUS" == "201" ]] || fail "propose: HTTP ${STATUS} ${BODY}"
PROPOSAL_ID="$(printf '%s' "$BODY" | jget "['proposal']['id']")"
P_STATUS="$(printf '%s' "$BODY" | jget "['proposal']['status']")"
PLAN="$(printf '%s' "$BODY" | jget "['proposal']['plan']['summary']")"
LAMPORTS="$(printf '%s' "$BODY" | jget "['proposal']['transaction']['lamports']")"
SIM_OK="$(printf '%s' "$BODY" | jget "['proposal']['simulation']['onchain']['ok']")"
EXECUTABLE="$(printf '%s' "$BODY" | jget "['proposal']['policy']['executable']")"
DECISION="$(printf '%s' "$BODY" | jget "['proposal']['policy']['authorization']['decision']")"
TIER="$(printf '%s' "$BODY" | jget "['proposal']['policy']['authorization']['tier']")"
POLICY_HASH="$(printf '%s' "$BODY" | jget "['proposal']['policy']['authorization']['policyHash']")"
EXEC_VIOL="$(printf '%s' "$BODY" | jget "['proposal']['policy']['executionViolations']")"
log "proposal ${PROPOSAL_ID}: ${P_STATUS}; ${PLAN}; lamports=${LAMPORTS}; simulation ok=${SIM_OK}; verdict ${DECISION}/${TIER} under ${POLICY_HASH}; executable=${EXECUTABLE}"
[[ "$P_STATUS" == "AWAITING_APPROVAL" ]] || fail "expected AWAITING_APPROVAL, got ${P_STATUS}: ${BODY}"
[[ "$EXECUTABLE" == "True" ]] || fail "policy says not executable: ${EXEC_VIOL}"
ev "proposal | \`${PROPOSAL_ID}\` — ${PLAN}; ${LAMPORTS} lamports; simulation ok=${SIM_OK}"
ev "policy | verdict **${DECISION}** (tier ${TIER}) under \`${POLICY_HASH}\`; executable=${EXECUTABLE}"

step "4. execute before approval → 409 (executionPreconditions, for real)"
RESP="$(api POST "/api/cryptobot/proposals/${PROPOSAL_ID}/execute")"; split_status
[[ "$STATUS" == "409" ]] || fail "expected 409 before approval, got ${STATUS}"
log "409: $(printf '%s' "$BODY" | jget "['message']")"
ev "execute before approval | 409 — $(printf '%s' "$BODY" | jget "['message']")"

step "5. human approval"
RESP="$(api POST "/api/cryptobot/proposals/${PROPOSAL_ID}/approve" '{"note":"demo: approved by the operator"}')"; split_status
[[ "$STATUS" == "200" ]] || fail "approve: HTTP ${STATUS} ${BODY}"
EXECUTABLE_AT="$(printf '%s' "$BODY" | jget "['approval']['executableAt']")"
log "APPROVED; executableAt=${EXECUTABLE_AT}"
ev "approval | APPROVED, executableAt=${EXECUTABLE_AT}"

step "6. timelock"
RESP="$(api POST "/api/cryptobot/proposals/${PROPOSAL_ID}/execute" '' -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')")"; split_status
if [[ "$STATUS" == "409" ]] && printf '%s' "$BODY" | grep -q Timelock; then
  MSG="$(printf '%s' "$BODY" | jget "['message']")"
  log "inside the timelock: 409 — ${MSG}"
  ev "timelock | 409 — ${MSG}"
  wait_s="$(python3 - "$EXECUTABLE_AT" <<'PY'
import sys, datetime
t = datetime.datetime.fromisoformat(sys.argv[1].replace('Z', '+00:00'))
print(max(0, int((t - datetime.datetime.now(datetime.timezone.utc)).total_seconds()) + 1))
PY
)"
  log "waiting ${wait_s}s for the lock to elapse"; sleep "$wait_s"
elif [[ "$STATUS" == "200" ]]; then
  fail "the timelock did not apply and the trade executed under a throwaway key; check CRYPTOBOT_TIMELOCK_* (body: ${BODY})"
else
  log "no timelock for this tier (ALLOW): HTTP ${STATUS} — $(printf '%s' "$BODY" | jget "['message']")"
  ev "timelock | none for verdict ${DECISION} (HTTP ${STATUS})"
fi

step "7-10. execute (Idempotency-Key) → validator attests → signer signs → sendTransaction → SUBMITTED → confirm → EXECUTED"
OPERATION_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
RESP="$(api POST "/api/cryptobot/proposals/${PROPOSAL_ID}/execute" '' -H "Idempotency-Key: ${OPERATION_ID}")"; split_status
[[ "$STATUS" == "200" ]] || fail "execute: HTTP ${STATUS} ${BODY}"
X_STATUS="$(printf '%s' "$BODY" | jget "['status']")"
SIGNATURE="$(printf '%s' "$BODY" | jget "['execution']['signature']")"
EXPLORER="$(printf '%s' "$BODY" | jget "['execution']['explorerUrl']")"
X_ERROR="$(printf '%s' "$BODY" | jget "['execution']['error']")"
log "status=${X_STATUS} signature=${SIGNATURE} error=${X_ERROR}"
[[ "$X_STATUS" == "SUBMITTED" || "$X_STATUS" == "EXECUTED" ]] || fail "execution ended ${X_STATUS}: ${X_ERROR}"
[[ -n "$SIGNATURE" && "$SIGNATURE" != "None" ]] || fail "no signature: ${BODY}"

step "the chain, asked directly: getSignatureStatuses(${SIGNATURE})"
CHAIN=""; SLOT=""; CONF=""
for i in $(seq 1 60); do
  CHAIN="$(rpc "$HOST_RPC" getSignatureStatuses "[[\"${SIGNATURE}\"],{\"searchTransactionHistory\":true}]")"
  CONF="$(printf '%s' "$CHAIN" | jget "['result']['value'][0]['confirmationStatus']")"
  SLOT="$(printf '%s' "$CHAIN" | jget "['result']['value'][0]['slot']")"
  ERR="$(printf '%s' "$CHAIN" | jget "['result']['value'][0]['err']")"
  [[ -n "$CONF" && "$CONF" != "None" ]] && break
  sleep 2
done
[[ -n "$CONF" && "$CONF" != "None" ]] || fail "signature never seen on ${HOST_RPC}: ${CHAIN}"
[[ "$ERR" == "None" ]] || fail "on-chain error: ${ERR}"
log "on chain: slot ${SLOT}, ${CONF}, err=${ERR}"

for i in $(seq 1 60); do
  RESP="$(api GET "/api/cryptobot/proposals/${PROPOSAL_ID}")"; split_status
  X_STATUS="$(printf '%s' "$BODY" | jget "['proposal']['status']")"
  [[ "$X_STATUS" == "EXECUTED" ]] && break
  [[ "$X_STATUS" == "FAILED" ]] && fail "proposal FAILED: $(printf '%s' "$BODY" | jget "['proposal']['execution']")"
  sleep 2
done
[[ "$X_STATUS" == "EXECUTED" ]] || fail "proposal is ${X_STATUS}, not EXECUTED (the reconciler closes it later; see /events)"
X_CONF="$(printf '%s' "$BODY" | jget "['proposal']['execution']['confirmationStatus']")"
AUDIT="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(' → '.join(e['eventType'] for e in d['audit']))")"
VALIDATOR="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); e=[x for x in d['audit'] if x['eventType']=='EXECUTION_VALIDATED'][0]['payload']; print(f\"{e['validator']} decision={e['decision']} verdictHash={e['verdictHash']}\")")"
log "EXECUTED (${X_CONF}); audit: ${AUDIT}"
ev "execution | operationId \`${OPERATION_ID}\`, signature \`${SIGNATURE}\`, slot ${SLOT}, ${CONF} on chain (RPC), ${X_CONF} in the service"
ev "explorer | ${EXPLORER}"
ev "validator | ${VALIDATOR}"
ev "audit | ${AUDIT}"

step "idempotency: the same Idempotency-Key again → same signature, no second transaction"
RESP="$(api POST "/api/cryptobot/proposals/${PROPOSAL_ID}/execute" '' -H "Idempotency-Key: ${OPERATION_ID}")"; split_status
SIG2="$(printf '%s' "$BODY" | jget "['execution']['signature']")"
[[ "$STATUS" == "200" && "$SIG2" == "$SIGNATURE" ]] || fail "replay changed the result: HTTP ${STATUS} sig=${SIG2}"
log "replay: HTTP 200, same signature"
ev "idempotency | replay of \`${OPERATION_ID}\` → HTTP 200, same signature, no second tx"

step "outbox + DLQ"
RESP="$(api GET "/api/cryptobot/proposals/${PROPOSAL_ID}/events")"; split_status
EVENTS="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(', '.join(f\"{e['eventType']}({e['status']})\" for e in d['events'])); print('deadLetters=' + str(len(d['deadLetters'])))")"
log "$EVENTS"
ev "outbox | ${EVENTS//$'\n'/; }"

step "11. EXECUTION receipt + verify"
RESP="$(api GET "/api/cryptobot/proposals/${PROPOSAL_ID}/receipts")"; split_status
RECEIPT="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); r=[x for x in d if x['body']['kind']=='EXECUTION']; print(r[-1]['receiptHash'] if r else '')")"
KINDS="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(' → '.join(x['body']['kind'] for x in d))")"
[[ -n "$RECEIPT" ]] || fail "no EXECUTION receipt among: ${KINDS}"
RESP="$(api POST "/api/cryptobot/receipts/${RECEIPT}/verify" '{}')"; split_status
VALID="$(printf '%s' "$BODY" | jget "['valid']")"; SIGVALID="$(printf '%s' "$BODY" | jget "['signatureValid']")"
[[ "$VALID" == "True" && "$SIGVALID" == "True" ]] || fail "receipt does not verify: ${BODY}"
log "receipts: ${KINDS}; EXECUTION ${RECEIPT} valid=${VALID} signatureValid=${SIGVALID}"
ev "receipts | ${KINDS}"
ev "receipt EXECUTION | \`${RECEIPT}\` — verify: valid=${VALID}, signatureValid=${SIGVALID}"

step "12. mainnet is fail-closed in the same run (read-only mainnet wallet → paper trade → execute 409)"
MAINNET="${CRYPTOBOT_E2E_MAINNET_WALLET:-MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr}"
RESP="$(api POST /api/cryptobot/wallets "{\"address\":\"${MAINNET}\",\"cluster\":\"mainnet-beta\",\"label\":\"read-only\"}")"; split_status
if [[ "$STATUS" == "201" ]]; then
  M_WALLET_ID="$(printf '%s' "$BODY" | jget "['wallet']['id']")"
  RESP="$(api POST "/api/cryptobot/wallets/${M_WALLET_ID}/proposals" '{"kind":"REBALANCE","targetWeights":{"SOL":70}}')"; split_status
  if [[ "$STATUS" == "201" ]]; then
    M_ID="$(printf '%s' "$BODY" | jget "['proposal']['id']")"
    M_STATUS="$(printf '%s' "$BODY" | jget "['proposal']['status']")"
    M_VIOL="$(printf '%s' "$BODY" | jget "['proposal']['policy']['executionViolations']")"
    [[ "$M_STATUS" == "AWAITING_APPROVAL" ]] && api POST "/api/cryptobot/proposals/${M_ID}/approve" '{"note":"demo"}' >/dev/null
    RESP="$(api POST "/api/cryptobot/proposals/${M_ID}/execute" '' -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')")"; split_status
    [[ "$STATUS" == "409" ]] || fail "mainnet execute returned ${STATUS}: ${BODY}"
    M_MSG="$(printf '%s' "$BODY" | jget "['message']")"
    log "mainnet proposal ${M_ID}: execute → 409 — ${M_MSG}"
    ev "mainnet fail-closed | proposal \`${M_ID}\` on \`${MAINNET}\` → execute **409** — ${M_MSG}; recorded violations: ${M_VIOL}"
  else
    warn "could not plan on the mainnet wallet (HTTP ${STATUS}); mainnet check skipped"
    ev "mainnet fail-closed | skipped: planning on the read-only wallet failed (HTTP ${STATUS})"
  fi
else
  warn "mainnet RPC/pricing unavailable (HTTP ${STATUS}); mainnet check skipped"
  ev "mainnet fail-closed | skipped: mainnet wallet registration failed (HTTP ${STATUS})"
fi

# ---- 5. evidence --------------------------------------------------------------------------------
SERVICE_COMMIT="$(git -C "$PROJECT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
{
  echo "# CryptoBot E2E — evidence ${TS}"
  echo
  echo "- mode: ${MODE}"
  echo "- service commit: \`${SERVICE_COMMIT}\` · stack: docker-compose.demo.yml (service + signer + validator + postgres)"
  echo "- flags: CRYPTOBOT_EXECUTION_ENABLED=true · CRYPTOBOT_ALLOW_MAINNET=false · SIGNER_REQUIRE_ATTESTATION=true · timelock escalated ${CRYPTOBOT_TIMELOCK_ESCALATED:-20s}"
  echo "- policy pin: \`${VALIDATOR_POLICY_HASH:-unpinned}\`"
  echo
  echo "| step | evidence |"
  echo "|---|---|"
  for line in "${EV[@]}"; do echo "| ${line} |"; done
  echo
  echo "Secrets: none in this file. Wallet, vault and validator keys live in \`${DEMO_HOME}\`; tokens in \`.env.demo\`."
} > "$EVIDENCE"
log "evidence written: ${EVIDENCE}"
cat "$EVIDENCE" >&2
