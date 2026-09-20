#!/usr/bin/env bash
# shellcheck disable=SC2034  # RESP/TOKEN/BASE/CURL_OPTS are read by the helpers sourced from lib.sh
# KAN-570 — the demo, end to end, by API, with evidence. KAN-571 — with a failure injected.
#
#   scripts/demo/e2e-devnet.sh [--local-validator] [--no-up] [--keep] [--sell-sol 1] [--it] [--chaos <mode>]
#                              [--env-file <f>] [--base-url <url> --token-env <VAR>] [--evidence <f>] [--summary <f>]
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
# --chaos <mode> (KAN-571, HK-3) injects a failure at broadcast time and shows the recovery:
#     uncertain        the tx IS broadcast, the RPC answer is lost ⇒ EXECUTION_BROADCAST_UNCERTAIN, row
#                      EXECUTING+SIGNED ⇒ the reconciler finds it confirmed ⇒ EXECUTED (no retry)
#     confirm-timeout  broadcast ok, the confirmation poll fails ⇒ SUBMITTED ⇒ reconciler ⇒ EXECUTED
#     rpc-down         nothing is sent and the RPC stays down ⇒ no verdict ⇒ dead letter (ambiguous)
#                      ⇒ the RPC comes back ⇒ POST /dead-letters/{id}/requeue ⇒ blockhash expired unseen
#                      ⇒ idempotent retry (same operationId, NEW signature) ⇒ EXECUTED; the evidence proves
#                      one transaction on chain and one operationId (no double execution).
#   With --it, --chaos sets CRYPTOBOT_E2E_CHAOS for E2EDevnetIT (rpc-down by default; `off` skips it).
# --it runs E2EDevnetIT (Failsafe, profile e2e-devnet) against the same stack instead of curl.
# --no-up assumes the stack is already up. --keep leaves it running at the end (default: stop).
# --base-url <url> (KAN-575, HK-7) runs the same flow against a service that is NOT this compose
#   (UAT, or a stack run.sh already brought up): no docker, no stop at the end; the bearer comes from
#   the variable named by --token-env (a JWT from Life Engine Auth) or, if absent, is minted from
#   JWT_SECRET of the env file. --env-file (default .env.demo) must carry DEMO_WALLET_ADDRESS,
#   CRYPTOBOT_REBALANCE_VAULT and CRYPTOBOT_SOLANA_DEVNET_RPC. CRYPTOBOT_DEMO_CURL_OPTS adds curl
#   arguments to every call (e.g. --resolve for a UAT host without public DNS).
# --evidence <f> / --summary <f>: where the Markdown evidence and a key=value summary (for run.sh) go.
# Never prints a secret: the token is minted in memory from JWT_SECRET, the keys are never read.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "${HERE}/../.." && pwd)"
# shellcheck disable=SC1091
source "${HERE}/lib.sh"

LOCAL_VALIDATOR=0; UP=1; KEEP=0; RUN_IT=0; SELL_SOL=1; CHAOS=""
ENV_FILE="${PROJECT}/.env.demo"; BASE_URL=""; TOKEN_ENV=""; EVIDENCE=""; SUMMARY=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --local-validator) LOCAL_VALIDATOR=1; shift ;;
    --no-up) UP=0; shift ;;
    --keep) KEEP=1; shift ;;
    --it) RUN_IT=1; shift ;;
    --sell-sol) SELL_SOL="$2"; shift 2 ;;
    --chaos) CHAOS="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --base-url) BASE_URL="${2%/}"; UP=0; shift 2 ;;
    --token-env) TOKEN_ENV="$2"; shift 2 ;;
    --evidence) EVIDENCE="$2"; shift 2 ;;
    --summary) SUMMARY="$2"; shift 2 ;;
    -h|--help) sed -n 3,40p "$0"; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
done
case "$CHAOS" in ""|uncertain|confirm-timeout|rpc-down) ;; *) fail "--chaos must be uncertain | confirm-timeout | rpc-down (got: $CHAOS)" ;; esac
[[ -n "$BASE_URL" && "$RUN_IT" -eq 1 ]] && fail "--it runs against the local compose only (E2EDevnetIT); drop --base-url"

need curl; need python3
[[ -n "$BASE_URL" ]] || need docker
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
if [[ -n "$BASE_URL" ]]; then
  BASE="$BASE_URL"
  MODE="remote ${BASE_URL} · ${MODE}"
else
  BASE="http://127.0.0.1:${CRYPTOBOT_DEMO_PORT:-8091}"
fi
# Extra curl arguments for every call (e.g. --resolve host:443:ip for a UAT without public DNS).
read -r -a CURL_OPTS <<< "${CRYPTOBOT_DEMO_CURL_OPTS:-}"
WALLET="${DEMO_WALLET_ADDRESS:?}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="${PROJECT}/out"; mkdir -p "$OUT_DIR"
[[ -n "$EVIDENCE" ]] || EVIDENCE="${OUT_DIR}/evidence-${TS}.md"
mkdir -p "$(dirname "$EVIDENCE")"
# summary <key> <value>: one line per fact, for run.sh (KAN-575). Never a secret.
summary() { [[ -n "$SUMMARY" ]] && printf '%s=%s\n' "$1" "$2" >> "$SUMMARY" || true; }
if [[ -n "$SUMMARY" ]]; then mkdir -p "$(dirname "$SUMMARY")"; : > "$SUMMARY"; fi
summary MODE "$MODE"; summary CHAOS "${CHAOS:-none}"; summary WALLET "$WALLET"; summary RPC "$HOST_RPC"; summary EVIDENCE "$EVIDENCE"

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
  if curl -fsS -m 3 "${CURL_OPTS[@]}" "${BASE}/api/cryptobot/health" 2>/dev/null | grep -q '"UP"'; then break; fi
  sleep 2
  [[ "$i" -eq 90 ]] && { [[ -z "$BASE_URL" ]] && "${COMPOSE[@]}" logs --tail 50 cryptobot-service >&2; fail "service not UP at ${BASE}"; }
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
      CRYPTOBOT_E2E_CHAOS="${CHAOS:-${CRYPTOBOT_E2E_CHAOS:-rpc-down}}" \
      MAVEN_OPTS="${MAVEN_OPTS:--Xmx1g}" ./mvnw -q -B -Pe2e-devnet verify )
  log "evidence: ${OUT_DIR}/evidence-${TS}-it.txt"
  exit 0
fi

# ---- 3. the flow, by API ------------------------------------------------------------------------
if [[ -n "$TOKEN_ENV" && -n "${!TOKEN_ENV:-}" ]]; then
  TOKEN="${!TOKEN_ENV}"   # a JWT issued by Life Engine Auth (remote target); never printed
  log "bearer: from \$${TOKEN_ENV}"
else
  [[ -n "${JWT_SECRET:-}" ]] || fail "no bearer: set --token-env <VAR> (a JWT from Auth) or JWT_SECRET in ${ENV_FILE}"
  TOKEN="$(jwt_hs256 "$JWT_SECRET" "$(python3 -c 'import uuid; print(uuid.uuid4())')" "demo@cryptobot.local")"
fi
# api / jget / split_status / step come from lib.sh (BASE, TOKEN and CURL_OPTS are set above).
EV=() ; ev() { EV+=("$*"); }

step "0. register the devnet wallet the signer controls"
RESP="$(api POST /api/cryptobot/wallets "{\"address\":\"${WALLET}\",\"cluster\":\"devnet\",\"label\":\"KAN-570 demo\"}")"; split_status
[[ "$STATUS" == "201" ]] || fail "register: HTTP ${STATUS} ${BODY}"
WALLET_ID="$(printf '%s' "$BODY" | jget "['wallet']['id']")"
TOTAL_USD="$(printf '%s' "$BODY" | jget "['snapshot']['totalUsd']")"
RISK="$(printf '%s' "$BODY" | jget "['risk']['overall']")"
log "wallet ${WALLET_ID}: total \$${TOTAL_USD}, risk ${RISK}"
ev "wallet | \`${WALLET}\` (id ${WALLET_ID}), portfolio \$${TOTAL_USD}, risk ${RISK}"
summary WALLET_ID "$WALLET_ID"; summary TOTAL_USD "$TOTAL_USD"; summary RISK "$RISK"
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
summary PROPOSAL_ID "$PROPOSAL_ID"; summary PLAN "$PLAN"; summary LAMPORTS "$LAMPORTS"; summary DECISION "$DECISION"; summary TIER "$TIER"; summary POLICY_HASH "$POLICY_HASH"

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
# Only probe the lock when there is one: a small trade is ALLOW (no timelock) and an execute "probe"
# with a throwaway key would be the real execution (KAN-571 found this with a 2 SOL wallet).
wait_s="$(python3 - "$EXECUTABLE_AT" <<'PY'
import sys, datetime
t = datetime.datetime.fromisoformat(sys.argv[1].replace('Z', '+00:00'))
print(max(0, int((t - datetime.datetime.now(datetime.timezone.utc)).total_seconds()) + 1))
PY
)"
if (( wait_s > 1 )); then
  RESP="$(api POST "/api/cryptobot/proposals/${PROPOSAL_ID}/execute" '' -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')")"; split_status
  if [[ "$STATUS" == "409" ]] && printf '%s' "$BODY" | grep -q Timelock; then
    MSG="$(printf '%s' "$BODY" | jget "['message']")"
    log "inside the timelock: 409 — ${MSG}"
    ev "timelock | 409 — ${MSG}"
  elif [[ "$STATUS" == "200" ]]; then
    fail "the timelock did not apply and the trade executed under a throwaway key; check CRYPTOBOT_TIMELOCK_* (body: ${BODY})"
  else
    fail "expected the timelock 409, got HTTP ${STATUS}: ${BODY}"
  fi
  log "waiting ${wait_s}s for the lock to elapse"; sleep "$wait_s"
else
  log "no timelock for verdict ${DECISION}/${TIER} (executableAt=${EXECUTABLE_AT} already reached)"
  ev "timelock | none for verdict ${DECISION} (tier ${TIER}): executableAt=${EXECUTABLE_AT}"
fi

OPERATION_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
VAULT="${CRYPTOBOT_REBALANCE_VAULT:?}"
# Transfers into the vault before the execution: the on-chain count the "no double execution" proof compares against.
vault_tx_count() { rpc "$HOST_RPC" getSignaturesForAddress "[\"${VAULT}\", {\"limit\": 1000, \"commitment\": \"confirmed\"}]" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len([s for s in d.get("result",[]) if s.get("err") is None]))'; }
VAULT_TXS_BEFORE="$(vault_tx_count)"
chain_status() { rpc "$HOST_RPC" getSignatureStatuses "[[\"$1\"],{\"searchTransactionHistory\":true}]" | python3 -c 'import json,sys; v=json.load(sys.stdin).get("result",{}).get("value",[None])[0]; print("never-seen" if v is None else (("error:"+json.dumps(v["err"])) if v.get("err") else v.get("confirmationStatus")))'; }
# The KAN-571 series, without the common tags (application/commit/environment/service/version) and without the zero placeholders.
kan571_metrics() { { curl -fsS -m 5 "${CURL_OPTS[@]}" "${BASE}/actuator/prometheus" 2>/dev/null || echo "actuator not reachable from here (403 behind the edge is expected in UAT)"; } \
  | grep -E '^cryptobot_(dead_letter_open|dead_letter_total|reconciliation_total)|^actuator' | grep -v ' 0.0$' \
  | sed -E 's/(application|commit|environment|service|version)="[^"]*",?//g; s/,\}/}/; s/\{\}//' | tr '\n' ';' || true; }
audit_types() { printf '%s' "$1" | python3 -c "import json,sys; d=json.load(sys.stdin); print(' → '.join(e['eventType'] for e in d['audit']))"; }
audit_field() { printf '%s' "$1" | python3 -c "import json,sys; d=json.load(sys.stdin); e=[x for x in d['audit'] if x['eventType']==sys.argv[1]]; print(e[-1]['payload'].get(sys.argv[2],'') if e else '')" "$2" "$3"; }

if [[ -n "$CHAOS" ]]; then
  # ---- KAN-571: the failure is injected, the recovery is watched -------------------------------
  step "7. inject the failure: chaos '${CHAOS}' armed on the service (demo-only endpoint)"
  SHOTS=1; [[ "$CHAOS" == "rpc-down" ]] && SHOTS=-1   # rpc-down stays down until we bring it back
  RESP="$(api PUT /api/cryptobot/demo/chaos "{\"broadcast\":\"${CHAOS}\",\"shots\":${SHOTS}}")"; split_status
  [[ "$STATUS" == "200" ]] || fail "chaos endpoint: HTTP ${STATUS} ${BODY} (is CRYPTOBOT_CHAOS_ENABLED=true in the demo compose?)"
  log "armed: $(printf '%s' "$BODY" | jget "['broadcast']") shots=$(printf '%s' "$BODY" | jget "['shotsLeft']")"
  ev "chaos injected | mode **${CHAOS}** armed via \`PUT /api/cryptobot/demo/chaos\` (shots ${SHOTS}); vault had ${VAULT_TXS_BEFORE} confirmed transfers before"

  step "8. execute (Idempotency-Key ${OPERATION_ID}) under the fault"
  RESP="$(api POST "/api/cryptobot/proposals/${PROPOSAL_ID}/execute" '' -H "Idempotency-Key: ${OPERATION_ID}")"; split_status
  [[ "$STATUS" == "200" ]] || fail "execute: HTTP ${STATUS} ${BODY}"
  X_STATUS="$(printf '%s' "$BODY" | jget "['status']")"; XR_STATUS="$(printf '%s' "$BODY" | jget "['execution']['status']")"
  SIG1="$(printf '%s' "$BODY" | jget "['execution']['signature']")"
  [[ -n "$SIG1" && "$SIG1" != "None" ]] || fail "no signature persisted before the broadcast: ${BODY}"
  RESP="$(api GET "/api/cryptobot/proposals/${PROPOSAL_ID}")"; split_status
  AUDIT1="$(audit_types "$BODY")"
  case "$CHAOS" in
    uncertain|rpc-down)
      [[ "$X_STATUS" == "EXECUTING" && "$XR_STATUS" == "SIGNED" ]] || fail "expected EXECUTING+SIGNED after an uncertain broadcast, got ${X_STATUS}/${XR_STATUS}: ${BODY}"
      [[ "$AUDIT1" == *EXECUTION_BROADCAST_UNCERTAIN* ]] || fail "no EXECUTION_BROADCAST_UNCERTAIN in the audit: ${AUDIT1}"
      UNC_ERR="$(audit_field "$BODY" EXECUTION_BROADCAST_UNCERTAIN error)"
      log "row in flight: ${X_STATUS}+${XR_STATUS}, signature ${SIG1} persisted, audit: ${AUDIT1}"
      ev "failure | HTTP 200 → proposal **${X_STATUS}** (execution ${XR_STATUS}), signature \`${SIG1}\` persisted before the broadcast; audit \`EXECUTION_BROADCAST_UNCERTAIN\` — ${UNC_ERR}" ;;
    confirm-timeout)
      [[ "$X_STATUS" == "SUBMITTED" ]] || fail "expected SUBMITTED after an interrupted confirmation, got ${X_STATUS}: ${BODY}"
      log "row in flight: SUBMITTED, signature ${SIG1}; audit: ${AUDIT1}"
      ev "failure | HTTP 200 → proposal **SUBMITTED**, signature \`${SIG1}\`; the confirmation poll was cut (audit: ${AUDIT1##*→ })" ;;
  esac
  RESP="$(api GET /api/cryptobot/demo/chaos)"; split_status
  FAULTS="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print('; '.join(f\"{f['method']}: {f['detail']}\" for f in d['faults']))")"
  log "faults injected: ${FAULTS}"
  ev "faults (service log) | ${FAULTS}"

  if [[ "$CHAOS" == "rpc-down" ]]; then
    step "9. the reconciler gets no verdict (RPC down) → after ${CRYPTOBOT_RECONCILIATION_MAX_ATTEMPTS:-3} attempts → dead letter"
    DL_ID=""; DL_REASON=""; DL_KIND=""
    for i in $(seq 1 90); do
      RESP="$(api GET "/api/cryptobot/dead-letters?proposalId=${PROPOSAL_ID}")"; split_status
      DL_ID="$(printf '%s' "$BODY" | jget "['deadLetters'][0]['id']")"
      [[ -n "$DL_ID" && "$DL_ID" != "None" ]] && break
      sleep 3
    done
    [[ -n "$DL_ID" && "$DL_ID" != "None" ]] || fail "no dead letter after 270 s: ${BODY}"
    DL_REASON="$(printf '%s' "$BODY" | jget "['deadLetters'][0]['reason']")"; DL_KIND="$(printf '%s' "$BODY" | jget "['deadLetters'][0]['payload']['kind']")"
    DL_OPEN="$(printf '%s' "$BODY" | jget "['open']")"
    log "dead letter ${DL_ID} kind=${DL_KIND} open=${DL_OPEN}: ${DL_REASON}"
    METRICS="$(kan571_metrics)"
    log "metrics: ${METRICS}"
    ev "dead letter | \`${DL_ID}\` kind **${DL_KIND}**, open=${DL_OPEN} — ${DL_REASON}"
    ev "metrics after DLQ | \`${METRICS}\`"
    RESP="$(api GET "/api/cryptobot/proposals/${PROPOSAL_ID}")"; split_status
    ev "audit at DLQ | $(audit_types "$BODY")"

    step "10. the RPC comes back (chaos disarmed)"
    RESP="$(api DELETE /api/cryptobot/demo/chaos)"; split_status
    [[ "$STATUS" == "200" ]] || fail "disarm: HTTP ${STATUS}"
    log "chaos disarmed; signature ${SIG1} on chain: $(chain_status "$SIG1")"

    step "11. human decision by API: POST /dead-letters/${DL_ID}/requeue"
    RESP="$(api POST "/api/cryptobot/dead-letters/${DL_ID}/requeue" '{"note":"demo: RPC is back, let the reconciler try again"}')"; split_status
    [[ "$STATUS" == "200" ]] || fail "requeue: HTTP ${STATUS} ${BODY}"
    RQ_OUTCOME="$(printf '%s' "$BODY" | jget "['deadLetter']['outcome']")"; RQ_BY="$(printf '%s' "$BODY" | jget "['deadLetter']['resolvedBy']")"
    RQ_AT="$(printf '%s' "$BODY" | jget "['deadLetter']['resolvedAt']")"; RQ_RESULT="$(printf '%s' "$BODY" | jget "['reconciliation']")"
    RQ_STATUS="$(printf '%s' "$BODY" | jget "['proposal']['status']")"
    log "requeued: outcome=${RQ_OUTCOME} by=${RQ_BY} at=${RQ_AT}; reconciliation now: ${RQ_RESULT}; proposal ${RQ_STATUS}"
    [[ "$RQ_OUTCOME" == "REQUEUED" ]] || fail "expected REQUEUED, got ${RQ_OUTCOME}: ${BODY}"
    ev "requeue | dead letter resolved: outcome **${RQ_OUTCOME}**, resolvedBy \`${RQ_BY}\`, resolvedAt ${RQ_AT}; reconciliation right away: **${RQ_RESULT}** (proposal ${RQ_STATUS})"
    RESP="$(api POST "/api/cryptobot/dead-letters/${DL_ID}/requeue" '{"note":"double click"}')"; split_status
    [[ "$STATUS" == "409" ]] || fail "a second requeue must be a 409, got ${STATUS}"
    log "second requeue: 409 (one-shot)"
    ev "requeue replay | second POST → **409** $(printf '%s' "$BODY" | jget "['message']")"

    step "12. final state: the retry (same operationId, new signature) lands → EXECUTED"
    for i in $(seq 1 90); do
      RESP="$(api GET "/api/cryptobot/proposals/${PROPOSAL_ID}")"; split_status
      X_STATUS="$(printf '%s' "$BODY" | jget "['proposal']['status']")"
      [[ "$X_STATUS" == "EXECUTED" ]] && break
      [[ "$X_STATUS" == "FAILED" ]] && fail "proposal FAILED: $(printf '%s' "$BODY" | jget "['proposal']['execution']")"
      sleep 3
    done
    [[ "$X_STATUS" == "EXECUTED" ]] || fail "proposal is ${X_STATUS}, not EXECUTED after 270 s"
    SIGNATURE="$(printf '%s' "$BODY" | jget "['proposal']['execution']['signature']")"
    PREV_SIG="$(printf '%s' "$BODY" | jget "['proposal']['execution']['previousSignature']")"
    RETRIES="$(printf '%s' "$BODY" | jget "['proposal']['execution']['retries']")"
    AUDIT="$(audit_types "$BODY")"
    RETRY_OP="$(audit_field "$BODY" EXECUTION_RETRIED operationId)"; START_OP="$(audit_field "$BODY" EXECUTION_STARTED operationId)"
    FINAL_OP="$(printf '%s' "$BODY" | jget "['proposal']['operationId']")"
    [[ "$SIGNATURE" != "$SIG1" && "$PREV_SIG" == "$SIG1" && "$RETRIES" == "1" ]] || fail "expected a retried execution (new signature, previousSignature=${SIG1}, retries=1): $(printf '%s' "$BODY" | jget "['proposal']['execution']")"
    [[ "$AUDIT" == *"RECONCILIATION_AMBIGUOUS"*"DEAD_LETTER_REQUEUED"*"EXECUTION_RETRIED"*"EXECUTED"* ]] || fail "audit trail incomplete: ${AUDIT}"
    log "EXECUTED with signature #2 ${SIGNATURE} (previous ${PREV_SIG}, retries ${RETRIES}); audit: ${AUDIT}"
    ev "recovered | **EXECUTED** — signature #2 \`${SIGNATURE}\` (retries=${RETRIES}, previousSignature=\`${PREV_SIG}\`), operationId \`${FINAL_OP}\`"
    ev "audit (full) | ${AUDIT}"

    step "13. no double execution — the chain, asked directly"
    S1="$(chain_status "$SIG1")"; S2="$(chain_status "$SIGNATURE")"; VAULT_TXS_AFTER="$(vault_tx_count)"
    log "signature #1 ${SIG1}: ${S1}; signature #2 ${SIGNATURE}: ${S2}; vault transfers ${VAULT_TXS_BEFORE} → ${VAULT_TXS_AFTER}"
    [[ "$S1" == "never-seen" ]] || fail "signature #1 is on the chain (${S1}): that would be a double execution"
    [[ "$S2" == "confirmed" || "$S2" == "finalized" ]] || fail "signature #2 not confirmed on chain: ${S2}"
    [[ $((VAULT_TXS_AFTER - VAULT_TXS_BEFORE)) -eq 1 ]] || fail "vault received $((VAULT_TXS_AFTER - VAULT_TXS_BEFORE)) transfers, expected exactly 1"
    [[ "$START_OP" == "$FINAL_OP" && "$RETRY_OP" == "$FINAL_OP" && "$FINAL_OP" == "$OPERATION_ID" ]] || fail "operationId drifted: started=${START_OP} retried=${RETRY_OP} final=${FINAL_OP} key=${OPERATION_ID}"
    ev "no double execution | on chain: signature #1 **${S1}**, signature #2 **${S2}**; vault transfers ${VAULT_TXS_BEFORE} → ${VAULT_TXS_AFTER} (**+1**); operationId identical in EXECUTION_STARTED, EXECUTION_RETRIED and the row (\`${OPERATION_ID}\`)"
    summary DEAD_LETTER_ID "$DL_ID"; summary DEAD_LETTER_KIND "$DL_KIND"; summary REQUEUE_OUTCOME "$RQ_OUTCOME"; summary PREVIOUS_SIGNATURE "$PREV_SIG"; summary RETRIES "$RETRIES"
    summary SIG1_ON_CHAIN "$S1"; summary SIG2_ON_CHAIN "$S2"; summary VAULT_TXS_BEFORE "$VAULT_TXS_BEFORE"; summary VAULT_TXS_AFTER "$VAULT_TXS_AFTER"
    RESP="$(api GET "/api/cryptobot/dead-letters")"; split_status
    ev "DLQ after | open=$(printf '%s' "$BODY" | jget "['open']") (GET /api/cryptobot/dead-letters)"
    METRICS="$(kan571_metrics)"
    ev "metrics after | \`${METRICS}\`"
    CONF="$S2"; SLOT="$(rpc "$HOST_RPC" getSignatureStatuses "[[\"${SIGNATURE}\"],{\"searchTransactionHistory\":true}]" | jget "['result']['value'][0]['slot']")"
    EXPLORER="$(printf '%s' "$BODY" | jget "['deadLetters']")"; EXPLORER="https://explorer.solana.com/tx/${SIGNATURE}?cluster=devnet"
  else
    step "9. the reconciler (startup + every ${CRYPTOBOT_RECONCILIATION_INTERVAL:-10s}, grace ${CRYPTOBOT_RECONCILIATION_GRACE:-20s}) asks the chain and closes the trade — no retry"
    for i in $(seq 1 60); do
      RESP="$(api GET "/api/cryptobot/proposals/${PROPOSAL_ID}")"; split_status
      X_STATUS="$(printf '%s' "$BODY" | jget "['proposal']['status']")"
      [[ "$X_STATUS" == "EXECUTED" ]] && break
      [[ "$X_STATUS" == "FAILED" ]] && fail "proposal FAILED: $(printf '%s' "$BODY" | jget "['proposal']['execution']")"
      sleep 3
    done
    [[ "$X_STATUS" == "EXECUTED" ]] || fail "proposal is ${X_STATUS}, not EXECUTED after 180 s"
    SIGNATURE="$(printf '%s' "$BODY" | jget "['proposal']['execution']['signature']")"
    RETRIES="$(printf '%s' "$BODY" | jget "['proposal']['execution']['retries']")"
    AUDIT="$(audit_types "$BODY")"
    [[ "$SIGNATURE" == "$SIG1" && "$RETRIES" == "0" ]] || fail "expected the SAME signature closed by reconciliation, got ${SIGNATURE} retries=${RETRIES}"
    [[ "$AUDIT" == *"RECONCILED"* ]] || fail "no RECONCILED in the audit: ${AUDIT}"
    RECON_FROM="$(audit_field "$BODY" RECONCILED from)"
    log "EXECUTED by reconciliation (from ${RECON_FROM}), same signature; audit: ${AUDIT}"
    ev "recovered | **EXECUTED** by the reconciler (RECONCILED from ${RECON_FROM} → EXECUTED), same signature \`${SIGNATURE}\`, retries=0"
    ev "audit (full) | ${AUDIT}"
    step "no double execution — the chain, asked directly"
    S1="$(chain_status "$SIG1")"; VAULT_TXS_AFTER="$(vault_tx_count)"
    [[ "$S1" == "confirmed" || "$S1" == "finalized" ]] || fail "signature not confirmed on chain: ${S1}"
    [[ $((VAULT_TXS_AFTER - VAULT_TXS_BEFORE)) -eq 1 ]] || fail "vault received $((VAULT_TXS_AFTER - VAULT_TXS_BEFORE)) transfers, expected exactly 1"
    log "signature ${SIG1}: ${S1}; vault transfers ${VAULT_TXS_BEFORE} → ${VAULT_TXS_AFTER}"
    ev "no double execution | on chain: signature **${S1}**; vault transfers ${VAULT_TXS_BEFORE} → ${VAULT_TXS_AFTER} (**+1**); operationId \`${OPERATION_ID}\`"
    summary RECONCILED_FROM "$RECON_FROM"; summary RETRIES "$RETRIES"; summary SIG1_ON_CHAIN "$S1"; summary VAULT_TXS_BEFORE "$VAULT_TXS_BEFORE"; summary VAULT_TXS_AFTER "$VAULT_TXS_AFTER"
    RESP="$(api GET "/api/cryptobot/dead-letters")"; split_status
    ev "DLQ after | open=$(printf '%s' "$BODY" | jget "['open']")"
    METRICS="$(kan571_metrics)"
    ev "metrics after | \`${METRICS}\`"
    CONF="$S1"; SLOT="$(rpc "$HOST_RPC" getSignatureStatuses "[[\"${SIGNATURE}\"],{\"searchTransactionHistory\":true}]" | jget "['result']['value'][0]['slot']")"
    EXPLORER="https://explorer.solana.com/tx/${SIGNATURE}?cluster=devnet"
  fi
  RESP="$(api GET "/api/cryptobot/proposals/${PROPOSAL_ID}")"; split_status
  X_CONF="$(printf '%s' "$BODY" | jget "['proposal']['execution']['confirmationStatus']")"
  VALIDATOR="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); e=[x for x in d['audit'] if x['eventType']=='EXECUTION_VALIDATED'][-1]['payload']; print(f\"{e['validator']} decision={e['decision']} verdictHash={e['verdictHash']}\")")"
  ev "execution | operationId \`${OPERATION_ID}\`, signature \`${SIGNATURE}\`, slot ${SLOT}, ${CONF} on chain (RPC), ${X_CONF} in the service"
  ev "explorer | ${EXPLORER}"
  ev "validator | ${VALIDATOR}"
  summary OPERATION_ID "$OPERATION_ID"; summary SIGNATURE "$SIGNATURE"; summary SLOT "$SLOT"; summary CONFIRMATION "$CONF"; summary EXPLORER "$EXPLORER"
  summary VALIDATOR "$VALIDATOR"; summary AUDIT "$AUDIT"; summary EXECUTED_AT "$(date +%s)"
else
step "7-10. execute (Idempotency-Key) → validator attests → signer signs → sendTransaction → SUBMITTED → confirm → EXECUTED"
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
summary OPERATION_ID "$OPERATION_ID"; summary SIGNATURE "$SIGNATURE"; summary SLOT "$SLOT"; summary CONFIRMATION "$CONF"; summary EXPLORER "$EXPLORER"
summary VALIDATOR "$VALIDATOR"; summary AUDIT "$AUDIT"; summary EXECUTED_AT "$(date +%s)"
fi

step "idempotency: the same Idempotency-Key again → same signature, no second transaction"
RESP="$(api POST "/api/cryptobot/proposals/${PROPOSAL_ID}/execute" '' -H "Idempotency-Key: ${OPERATION_ID}")"; split_status
SIG2="$(printf '%s' "$BODY" | jget "['execution']['signature']")"
[[ "$STATUS" == "200" && "$SIG2" == "$SIGNATURE" ]] || fail "replay changed the result: HTTP ${STATUS} sig=${SIG2}"
log "replay: HTTP 200, same signature"
ev "idempotency | replay of \`${OPERATION_ID}\` → HTTP 200, same signature, no second tx"

step "outbox + DLQ"
RESP="$(api GET "/api/cryptobot/proposals/${PROPOSAL_ID}/events")"; split_status
EVENTS="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(', '.join(f\"{e['eventType']}({e['status']})\" for e in d['events'])); print('deadLetters=' + str(len(d['deadLetters'])) + ''.join(f\" [{x['payload'].get('kind')} → {x.get('outcome') or 'OPEN'}]\" for x in d['deadLetters']))")"
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
summary RECEIPT_KINDS "$KINDS"; summary RECEIPT_EXECUTION "$RECEIPT"; summary RECEIPT_VALID "$VALID"; summary RECEIPT_SIGNATURE_VALID "$SIGVALID"

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
    summary MAINNET "409 — ${M_MSG}"
  else
    warn "could not plan on the mainnet wallet (HTTP ${STATUS}); mainnet check skipped"
    ev "mainnet fail-closed | skipped: planning on the read-only wallet failed (HTTP ${STATUS})"
    summary MAINNET "skipped (plan HTTP ${STATUS})"
  fi
else
  warn "mainnet RPC/pricing unavailable (HTTP ${STATUS}); mainnet check skipped"
  ev "mainnet fail-closed | skipped: mainnet wallet registration failed (HTTP ${STATUS})"
  summary MAINNET "skipped (register HTTP ${STATUS})"
fi

# ---- 5. evidence --------------------------------------------------------------------------------
SERVICE_COMMIT="$(git -C "$PROJECT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
{
  echo "# CryptoBot E2E — evidence ${TS}"
  echo
  echo "- mode: ${MODE}"
  [[ -n "$CHAOS" ]] && echo "- chaos (KAN-571): **${CHAOS}** injected at broadcast time; reconciliation interval ${CRYPTOBOT_RECONCILIATION_INTERVAL:-10s}, grace ${CRYPTOBOT_RECONCILIATION_GRACE:-20s}, max attempts ${CRYPTOBOT_RECONCILIATION_MAX_ATTEMPTS:-3}, max retries ${CRYPTOBOT_RECONCILIATION_MAX_RETRIES:-2}"
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
