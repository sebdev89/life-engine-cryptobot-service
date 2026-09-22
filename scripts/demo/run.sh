#!/usr/bin/env bash
# shellcheck disable=SC2034  # RESP/TOKEN/BASE/CURL_OPTS are read by the helpers sourced from lib.sh
# KAN-575 (HK-7) — the whole CryptoBot demo, one command, from zero, with a report.
#
#   scripts/demo/run.sh [--target local|uat] [--rpc auto|devnet|local] [--env-file <f>] [--sell-sol N]
#                       [--no-recovery] [--no-anchor] [--keep] [--out <dir>] [--dry-run]
#
# Acts — each one printed with its evidence as it happens, timed, and written to the report:
#   0 setup     tools · keys + .env.demo (generated once, never committed) · RPC choice · stack up
#               (local target: docker-compose.demo.yml — service + validator + signer + Postgres, and
#               solana-test-validator when the RPC is local) · health · SOL in the wallet
#   1 execute   request → plan → simulation → 13 rules + R_v + validator → execute-before-approval 409
#               → approval → timelock → execute (Idempotency-Key) → validator attests → signer signs
#               → Solana → SUBMITTED → confirmed on chain → EXECUTED → replay = same tx → outbox
#               → EXECUTION receipt verified → a mainnet intent in the same run → 409     (e2e-devnet.sh)
#   2 risk      an adversarial intent (dump 95 % of the position) → BLOCKED_BY_POLICY with the rules
#               that failed, approve → 409, execute → 409; then the cooldown the wallet is under; then
#               (KAN-572) a SECOND adversarial intent, wrong only in its price: one oracle source is made
#               to say −90 % (PUT /api/cryptobot/demo/price, demo profile only) → PRICE_DEVIATION, and
#               every source made 15 min old → PRICE_STALE — each with its Decision Receipt (RISK_DECISION
#               by policy-engine, L1) verified live and placed in the lineage
#   3 recovery  the RPC dies at broadcast → no verdict → dead letter → RPC back → requeue by API
#               → idempotent retry (same operationId, new signature) → EXECUTED; the chain, asked
#               directly, shows ONE transaction                        (e2e-devnet.sh --chaos rpc-down)
#   4 evidence  receipt DAG (parents of the EXECUTION receipt) → Merkle anchor of the receipts on
#               Solana (memo tx, finalized) → anchor verify → inclusion proof → metrics
#   5 report    out/demo-report-<ts>.md (+ out/run-<ts>/ with the per-act evidence and the full log);
#               the report and the log are grepped for every secret of the env file: 0 hits or exit 3.
#
# --target local (default): the compose stack on this machine. `CRYPTOBOT_DEMO_PROJECT` names the
#   compose project (default cryptobot-demo): a new name is a new stack — "from zero" without touching
#   a previous rehearsal. --rpc auto (default) runs on devnet when the wallet holds ≥ 0.6 SOL there,
#   otherwise on the local solana-test-validator (unlimited airdrop; explorer links do not resolve).
# --target uat: the same acts against a deployed service (KAN-574). Reads .env.demo-uat (see
#   .env.demo-uat.example): CRYPTOBOT_DEMO_BASE_URL, a JWT in CRYPTOBOT_DEMO_TOKEN or the Auth login
#   (CRYPTOBOT_DEMO_AUTH_URL/_USER/_PASSWORD), the signer's wallet, the vault, the RPC. No docker.
#   Act 3 needs the chaos endpoint (CRYPTOBOT_CHAOS_ENABLED, demo compose only): on a target without
#   it the act is SKIPPED and the report says why.
#   The price injection (act 2, KAN-572) needs CRYPTOBOT_CHAOS_ENABLED too: without it the price
#   intents are SKIPPED and the report says why.
# --keep leaves the local stack running (UI, curl, Postgres). --dry-run prints the plan and exits.
# Exit: 0 every act passed (SKIPPED allowed only where the target lacks the feature) · 1 an act
# failed (the report is still written, the failing act marked) · 3 a secret reached the report/log.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "${HERE}/../.." && pwd)"
# shellcheck disable=SC1091
source "${HERE}/lib.sh"

TARGET=local; RPC_MODE=auto; ENV_FILE=""; SELL_SOL=0.5; RECOVERY=1; ANCHOR=1; KEEP=0; OUT=""; DRY_RUN=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --target) TARGET="$2"; shift 2 ;;
    --rpc) RPC_MODE="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --sell-sol) SELL_SOL="$2"; shift 2 ;;
    --no-recovery) RECOVERY=0; shift ;;
    --no-anchor) ANCHOR=0; shift ;;
    --keep) KEEP=1; shift ;;
    --out) OUT="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n 3,38p "$0"; exit 0 ;;
    *) fail "unknown argument: $1 (see --help)" ;;
  esac
done
case "$TARGET" in local|uat) ;; *) fail "--target must be local | uat (got: $TARGET)" ;; esac
case "$RPC_MODE" in auto|devnet|local) ;; *) fail "--rpc must be auto | devnet | local (got: $RPC_MODE)" ;; esac
[[ "$TARGET" == "uat" && "$RPC_MODE" == "local" ]] && fail "--rpc local is the compose's solana-test-validator: not available with --target uat"
if [[ -z "$ENV_FILE" ]]; then
  ENV_FILE="${PROJECT}/.env.demo"; [[ "$TARGET" == "uat" ]] && ENV_FILE="${PROJECT}/.env.demo-uat"
fi
[[ -n "$OUT" ]] || OUT="${PROJECT}/out"

TS="$(date +%Y%m%d-%H%M%S)"
RUN_DIR="${OUT}/run-${TS}"
REPORT="${OUT}/demo-report-${TS}.md"
LOG="${RUN_DIR}/demo.log"
COOLDOWN_S="${CRYPTOBOT_POLICY_COOLDOWN:-60s}"; COOLDOWN_S="${COOLDOWN_S%s}"
COMPOSE=(); BASE=""; TOKEN=""; CURL_OPTS=(); HOST_RPC=""; RPC_LABEL=""; PRICE_ARMED=0; PRICE_NOTE=""; SERVICE_COMMIT="$(git -C "$PROJECT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
declare -A A1=() A3=()

# ---- act bookkeeping ------------------------------------------------------------------------------
ACT_NAMES=(); ACT_RESULTS=(); ACT_SECS=(); ACT_NOTES=(); EV2=(); EV4=(); METRICS_MD=""
CURRENT=-1; ACT_START=0; LAST_ERROR=""; RUN_START="$(date +%s)"
fail() { LAST_ERROR="$*"; printf '\033[1;31m[demo]\033[0m %s\n' "$*" >&2; exit 1; }
act_begin() { # act_begin "<name>"
  ACT_NAMES+=("$1"); ACT_RESULTS+=("RUNNING"); ACT_SECS+=(0); ACT_NOTES+=("")
  CURRENT=$(( ${#ACT_NAMES[@]} - 1 )); ACT_START="$(date +%s)"
  printf '\n\033[1;35m#### ACT %s — %s  (%s)\033[0m\n' "$CURRENT" "$1" "$(date -Is)" >&2
}
act_end() { # act_end PASS|SKIPPED "<note>"
  ACT_RESULTS[CURRENT]="$1"; ACT_NOTES[CURRENT]="${2:-}"; ACT_SECS[CURRENT]=$(( $(date +%s) - ACT_START ))
  printf '\033[1;35m#### ACT %s — %s: %s in %ss%s\033[0m\n' "$CURRENT" "${ACT_NAMES[CURRENT]}" "$1" "${ACT_SECS[CURRENT]}" "${2:+ — $2}" >&2
  CURRENT=-1
}
hms() { printf '%dm%02ds' $(( $1 / 60 )) $(( $1 % 60 )); }
read_summary() { # read_summary <file> <assoc-array-name>
  local -n _a="$2"; local k v
  while IFS='=' read -r k v; do [[ -n "$k" ]] && _a["$k"]="$v"; done < "$1"
}

# ---- report + secrets check, always (EXIT trap) ---------------------------------------------------
on_exit() {
  local rc=$?
  if (( CURRENT >= 0 )); then
    ACT_RESULTS[CURRENT]="FAILED"; ACT_NOTES[CURRENT]="${LAST_ERROR:-exit ${rc}}"; ACT_SECS[CURRENT]=$(( $(date +%s) - ACT_START ))
  fi
  if [[ "${PRICE_ARMED:-0}" -eq 1 ]]; then
    # Never leave the adversarial price armed in a stack that stays up (--keep) or in a rehearsal that failed mid-way.
    RESP="$(api DELETE /api/cryptobot/demo/price 2>/dev/null || true)"; PRICE_ARMED=0
  fi
  if [[ "$TARGET" == "local" && "$KEEP" -eq 0 && ${#COMPOSE[@]} -gt 0 && "$DRY_RUN" -eq 0 ]]; then
    log "stopping the demo stack (--keep to leave it running)"
    "${COMPOSE[@]}" stop >/dev/null 2>&1 || true
  fi
  [[ "$DRY_RUN" -eq 1 ]] && exit "$rc"
  write_report "$rc"
  # Close our end of the tee pipe and wait for tee: the log must be complete before it is grepped.
  exec 1>&3 2>&4
  wait "$TEE_PID" 2>/dev/null || true
  sed -i 's/\x1b\[[0-9;]*m//g' "$LOG"
  local sc=0 total=$(( $(date +%s) - RUN_START ))
  secrets_check 2>"${RUN_DIR}/secrets-check.txt" || sc=$?
  tee -a "$LOG" < "${RUN_DIR}/secrets-check.txt" >&2
  (( sc == 0 )) || exit 3
  if (( rc == 0 )); then
    printf '[demo] DEMO PASSED in %s %s — report: %s\n' "$(hms "$total")" "$([[ $total -lt 600 ]] && echo '(< 10 min ✓)' || echo '(over 10 min ✗)')" "$REPORT" | tee -a "$LOG" >&2
  else
    printf '[demo] DEMO FAILED in %s — %s — report: %s · log: %s\n' "$(hms "$total")" "${LAST_ERROR:-exit ${rc}}" "$REPORT" "$LOG" | tee -a "$LOG" >&2
  fi
  exit "$rc"
}
write_report() {
  local rc="$1" total=$(( $(date +%s) - RUN_START )) i
  {
    echo "# CryptoBot demo — ${TS} — $([[ "$rc" -eq 0 ]] && echo PASSED || echo FAILED)"
    echo
    echo "- target: **${TARGET}** · RPC: ${RPC_LABEL:-?} · service commit \`${SERVICE_COMMIT}\` · started $(date -d "@${RUN_START}" -Is) · total **$(hms "$total")** (goal < 10 min)"
    echo "- command: \`scripts/demo/run.sh --target ${TARGET} --rpc ${RPC_MODE}$([[ "$RECOVERY" -eq 0 ]] && echo ' --no-recovery')$([[ "$ANCHOR" -eq 0 ]] && echo ' --no-anchor')\` · evidence dir \`${RUN_DIR}\` · log \`${LOG}\`"
    [[ -n "${A1[MODE]:-}" ]] && echo "- mode: ${A1[MODE]}"
    [[ -n "$LAST_ERROR" ]] && echo "- **error:** ${LAST_ERROR}"
    echo
    echo "| act | result | time | note |"
    echo "|---|---|---|---|"
    for i in "${!ACT_NAMES[@]}"; do
      echo "| ${i} ${ACT_NAMES[$i]} | **${ACT_RESULTS[$i]}** | $(hms "${ACT_SECS[$i]}") | ${ACT_NOTES[$i]} |"
    done
    echo
    if [[ -f "${RUN_DIR}/act1-evidence.md" ]]; then
      echo "## Act 1 — request → policy → approval → timelock → validator → signer → Solana → confirmed → EXECUTED"
      echo
      grep -v '^# ' "${RUN_DIR}/act1-evidence.md"
      echo
    fi
    if (( ${#EV2[@]} > 0 )); then
      echo "## Act 2 — risk: the adversarial intents, blocked (policy; mint; price), a receipt caught tampered at rest, each with a verifiable Decision Receipt; the cooldown"
      echo
      echo "| step | evidence |"; echo "|---|---|"
      for line in "${EV2[@]}"; do echo "| ${line} |"; done
      echo
    fi
    if [[ -f "${RUN_DIR}/act3-evidence.md" ]]; then
      echo "## Act 3 — reconciliation + recovery: RPC down at broadcast → dead letter → requeue → idempotent retry"
      echo
      grep -v '^# ' "${RUN_DIR}/act3-evidence.md"
      echo
    fi
    if (( ${#EV4[@]} > 0 )); then
      echo "## Act 4 — evidence: receipt DAG, Merkle anchor on Solana, inclusion proof"
      echo
      echo "| step | evidence |"; echo "|---|---|"
      for line in "${EV4[@]}"; do echo "| ${line} |"; done
      echo
    fi
    if [[ -n "$METRICS_MD" ]]; then
      echo "## Metrics at the end of the run (non-zero cryptobot series, common tags stripped)"
      echo
      echo '```'; printf '%s\n' "$METRICS_MD"; echo '```'
      echo
    fi
    echo "## Secrets"
    echo
    echo "Wallet, vault and validator keys live in \`${DEMO_HOME}\` (0600); tokens and the JWT secret in \`${ENV_FILE}\` (gitignored)."
    echo "This report and the log were grepped for every secret value of that file after the run: see the console line \`secrets check\`."
  } > "$REPORT"
  log "report written: ${REPORT}"
}
# Every secret VALUE of the env file must be absent from the report and the log. Values under 12
# characters are not searched (a short token would false-positive on hashes).
secrets_check() {
  local k v hits=0 n=0
  [[ -f "$ENV_FILE" ]] || { log "secrets check: no env file, nothing to check"; return 0; }
  while IFS='=' read -r k v; do
    [[ "$k" =~ ^(JWT_SECRET|.*_TOKEN|.*_PASSWORD|.*_SIGNING_KEY|.*_SALT_SECRET)$ ]] || continue
    (( ${#v} >= 12 )) || continue
    n=$((n + 1))
    if grep -q -F -- "$v" "$REPORT" "$LOG" 2>/dev/null; then
      printf '\033[1;31m[demo]\033[0m SECRET LEAK: the value of %s appears in the report or the log\n' "$k" >&2; hits=$((hits + 1))
    fi
  done < "$ENV_FILE"
  if git -C "$PROJECT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    git -C "$PROJECT" check-ignore -q "$ENV_FILE" || { printf '\033[1;31m[demo]\033[0m %s is NOT gitignored\n' "$ENV_FILE" >&2; hits=$((hits + 1)); }
    git -C "$PROJECT" check-ignore -q "$REPORT" || { printf '\033[1;31m[demo]\033[0m %s is NOT gitignored\n' "$REPORT" >&2; hits=$((hits + 1)); }
  fi
  log "secrets check: ${n} secret values searched in the report and the log, ${hits} hits; env file and out/ gitignored"
  (( hits == 0 ))
}
trap on_exit EXIT

# ---- act 0: setup ---------------------------------------------------------------------------------
setup_local() {
  need docker; need curl; need python3; need git
  if [[ ! -f "$ENV_FILE" ]]; then
    log "no ${ENV_FILE}: generating keys + secrets (wallet-devnet.sh --no-airdrop; public keys only are printed)"
    "${HERE}/wallet-devnet.sh" --no-airdrop
  fi
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  local wallet="${DEMO_WALLET_ADDRESS:?}" devnet="${CRYPTOBOT_SOLANA_DEVNET_RPC:-https://api.devnet.solana.com}" bal=0
  if [[ "$RPC_MODE" == "auto" ]]; then
    bal="$(balance_lamports "$devnet" "$wallet" 2>/dev/null || echo 0)"
    if (( bal >= 600000000 )); then
      RPC_MODE=devnet; log "rpc auto → devnet: wallet ${wallet} holds ${bal} lamports on ${devnet}"
    else
      RPC_MODE=local; warn "rpc auto → local validator: wallet ${wallet} holds ${bal} lamports on ${devnet} (< 0.6 SOL). Fund it at https://faucet.solana.com for a devnet run with explorer links."
    fi
  fi
  COMPOSE=(docker compose -f "${PROJECT}/docker-compose.demo.yml" --env-file "$ENV_FILE")
  if [[ "$RPC_MODE" == "local" ]]; then
    COMPOSE+=(--profile local-validator)
    export CRYPTOBOT_SOLANA_DEVNET_RPC="http://solana-local:8899"
    HOST_RPC="http://127.0.0.1:${SOLANA_LOCAL_PORT:-8999}"; RPC_LABEL="local solana-test-validator (${HOST_RPC})"
  else
    HOST_RPC="$devnet"; RPC_LABEL="devnet (${HOST_RPC})"
  fi
  BASE="http://127.0.0.1:${CRYPTOBOT_DEMO_PORT:-8091}"
  log "compose project ${CRYPTOBOT_DEMO_PROJECT:-cryptobot-demo} · service ${BASE} · RPC ${RPC_LABEL}"
  if [[ "$DRY_RUN" -eq 1 ]]; then return 0; fi
  log "docker compose up -d --build (the first build on a clean machine takes a few minutes; later runs reuse the images)"
  "${COMPOSE[@]}" up -d --build
  wait_health
  TOKEN="$(jwt_hs256 "${JWT_SECRET:?}" "$(python3 -c 'import uuid; print(uuid.uuid4())')" "demo@cryptobot.local")"
  export CRYPTOBOT_DEMO_TOKEN="$TOKEN"
}
setup_uat() {
  need curl; need python3; need git
  [[ -f "$ENV_FILE" ]] || fail "no ${ENV_FILE}: copy .env.demo-uat.example and fill it in (base URL, token or Auth login, the signer's wallet, the vault)"
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  BASE="${CRYPTOBOT_DEMO_BASE_URL:?CRYPTOBOT_DEMO_BASE_URL missing in ${ENV_FILE}}"; BASE="${BASE%/}"
  read -r -a CURL_OPTS <<< "${CRYPTOBOT_DEMO_CURL_OPTS:-}"
  : "${DEMO_WALLET_ADDRESS:?DEMO_WALLET_ADDRESS missing in ${ENV_FILE} (the wallet the signer controls)}"
  : "${CRYPTOBOT_REBALANCE_VAULT:?CRYPTOBOT_REBALANCE_VAULT missing in ${ENV_FILE}}"
  HOST_RPC="${CRYPTOBOT_SOLANA_DEVNET_RPC:-https://api.devnet.solana.com}"; RPC_MODE=devnet; RPC_LABEL="devnet (${HOST_RPC}) via ${BASE}"
  log "target ${BASE} · RPC ${RPC_LABEL}"
  if [[ "$DRY_RUN" -eq 1 ]]; then return 0; fi
  wait_health
  if [[ -n "${CRYPTOBOT_DEMO_TOKEN:-}" ]]; then
    TOKEN="$CRYPTOBOT_DEMO_TOKEN"; log "bearer: CRYPTOBOT_DEMO_TOKEN from ${ENV_FILE}"
  else
    [[ -n "${CRYPTOBOT_DEMO_AUTH_URL:-}" && -n "${CRYPTOBOT_DEMO_AUTH_USER:-}" && -n "${CRYPTOBOT_DEMO_AUTH_PASSWORD:-}" ]] \
      || fail "no bearer: set CRYPTOBOT_DEMO_TOKEN or CRYPTOBOT_DEMO_AUTH_URL + _USER + _PASSWORD in ${ENV_FILE}"
    # The credentials travel on stdin (never on a command line) and the token is kept in memory.
    TOKEN="$(U="$CRYPTOBOT_DEMO_AUTH_USER" P="$CRYPTOBOT_DEMO_AUTH_PASSWORD" python3 -c 'import json,os; print(json.dumps({"email":os.environ["U"],"password":os.environ["P"]}))' \
      | curl -sS -m 30 "${CURL_OPTS[@]}" -X POST "$CRYPTOBOT_DEMO_AUTH_URL" -H 'content-type: application/json' --data-binary @- \
      | jget "['accessToken']")"
    [[ -n "$TOKEN" ]] || fail "login at ${CRYPTOBOT_DEMO_AUTH_URL} returned no accessToken"
    log "bearer: obtained from ${CRYPTOBOT_DEMO_AUTH_URL} for ${CRYPTOBOT_DEMO_AUTH_USER} (not shown)"
    export CRYPTOBOT_DEMO_TOKEN="$TOKEN"
  fi
}
wait_health() {
  log "waiting for ${BASE}/api/cryptobot/health"
  local i
  for i in $(seq 1 90); do
    if curl -fsS -m 3 "${CURL_OPTS[@]}" "${BASE}/api/cryptobot/health" 2>/dev/null | grep -q '"UP"'; then log "service UP"; return 0; fi
    sleep 2
  done
  [[ ${#COMPOSE[@]} -gt 0 ]] && "${COMPOSE[@]}" logs --tail 50 cryptobot-service >&2
  fail "service not UP at ${BASE} after 180 s"
}

# ---- act 2: risk ----------------------------------------------------------------------------------
act_risk() {
  local wid="${A1[WALLET_ID]:?}" resp_status body pid pstatus rules
  step "an adversarial intent on the same wallet: SOL → 5 % (dump 95 % of the position)"
  RESP="$(api POST "/api/cryptobot/wallets/${wid}/proposals" '{"kind":"REBALANCE","targetWeights":{"SOL":5},"reasoningSummary":"demo: adversarial — dump 95 % of the position in one trade"}')"; split_status
  resp_status="$STATUS"; body="$BODY"
  [[ "$resp_status" == "201" ]] || fail "adversarial propose: HTTP ${resp_status} ${body}"
  pid="$(printf '%s' "$body" | jget "['proposal']['id']")"; pstatus="$(printf '%s' "$body" | jget "['proposal']['status']")"
  rules="$(printf '%s' "$body" | python3 -c "import json,sys; d=json.load(sys.stdin)['proposal']['policy']; print('; '.join(f\"{v['rule']}: {v['message']}\" for v in d.get('violations', [])))")"
  local decision tier; decision="$(printf '%s' "$body" | jget "['proposal']['policy']['authorization']['decision']")"; tier="$(printf '%s' "$body" | jget "['proposal']['policy']['authorization']['tier']")"
  log "proposal ${pid}: ${pstatus}; verdict ${decision}/${tier}; violations: ${rules}"
  [[ "$pstatus" == "BLOCKED_BY_POLICY" ]] || fail "expected BLOCKED_BY_POLICY, got ${pstatus}: ${body}"
  EV2+=("adversarial intent | proposal \`${pid}\` SOL → 5 % → **${pstatus}**; verdict ${decision} (tier ${tier})")
  EV2+=("rules that failed | ${rules}")
  RESP="$(api POST "/api/cryptobot/proposals/${pid}/approve" '{"note":"demo: trying to approve a blocked proposal"}')"; split_status
  [[ "$STATUS" == "409" ]] || fail "approve of a blocked proposal must be 409, got ${STATUS}: ${BODY}"
  log "approve → 409: $(printf '%s' "$BODY" | jget "['message']")"
  EV2+=("approve | **409** — $(printf '%s' "$BODY" | jget "['message']")")
  RESP="$(api POST "/api/cryptobot/proposals/${pid}/execute" '' -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')")"; split_status
  [[ "$STATUS" == "409" ]] || fail "execute of a blocked proposal must be 409, got ${STATUS}: ${BODY}"
  log "execute → 409: $(printf '%s' "$BODY" | jget "['message']")"
  EV2+=("execute | **409** — $(printf '%s' "$BODY" | jget "['message']")")
  RESP="$(api GET "/api/cryptobot/proposals/${pid}/receipts")"; split_status
  local kinds risk_hash
  kinds="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(' → '.join(x['body']['kind'] for x in d))")"
  risk_hash="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); r=[x for x in d if x['body']['kind']=='RISK_DECISION']; print(r[-1]['receiptHash'] if r else '')")"
  if [[ -n "$risk_hash" ]]; then
    RESP="$(api POST "/api/cryptobot/receipts/${risk_hash}/verify" '{}')"; split_status
    log "receipts of the blocked proposal: ${kinds}; RISK_DECISION ${risk_hash} verify valid=$(printf '%s' "$BODY" | jget "['valid']")"
    EV2+=("receipts | ${kinds}; RISK_DECISION \`${risk_hash}\` — verify valid=$(printf '%s' "$BODY" | jget "['valid']"), signatureValid=$(printf '%s' "$BODY" | jget "['signatureValid']")")
  else
    EV2+=("receipts | ${kinds:-none}")
  fi

  # KAN-607 — two more failure demos, both cheap (no waiting, no chain): a mint outside the allowed
  # asset list, and a receipt caught tampered at rest.
  mint_not_allowed_demo "$wid"
  tampered_receipt_demo "${MINT_RISK_RECEIPT:-$risk_hash}"

  # The cooldown: after act 1 this wallet cannot trade again for CRYPTOBOT_POLICY_COOLDOWN (60 s in
  # production config). Act 3 needs an approvable proposal, so the demo waits it out — visibly.
  local executed_at="${A1[EXECUTED_AT]:-0}" ago wait_s
  ago=$(( $(date +%s) - executed_at )); wait_s=$(( COOLDOWN_S + 3 - ago ))
  if (( wait_s > 0 )); then
    log "policy COOLDOWN: this wallet executed ${ago}s ago; the next trade is only approvable after ${COOLDOWN_S}s — waiting ${wait_s}s"
    EV2+=("cooldown | rule \`COOLDOWN\` (${COOLDOWN_S}s) in force since the act-1 execution (${ago}s ago at this point); the demo waits ${wait_s}s before the next intent")
    sleep "$wait_s"
  else
    EV2+=("cooldown | rule \`COOLDOWN\` (${COOLDOWN_S}s) already elapsed (${ago}s since the act-1 execution)")
  fi
  act_price "$wid"
}

# KAN-607 — failure demo #1 (cheap): a mint outside the allowed asset list. The plan itself is fine
# (SOL → 50 %); what is wrong is where the counter side would land — BONK is not in
# cryptobot.policy.allowed-assets (SOL,USDC,USDT in the demo). ASSET_ALLOWLIST checks every leg's
# asset AND its counter asset, so this blocks before any price is even needed for BONK. Sets
# MINT_RISK_RECEIPT to the RISK_DECISION receipt hash for tampered_receipt_demo below.
MINT_RISK_RECEIPT=""
mint_not_allowed_demo() {
  local wid="$1" pid pstatus rules blocked_by kinds
  step "an adversarial mint: SOL → 50 %, funded in BONK — not in the allowed asset list — POST /wallets/${wid}/proposals"
  RESP="$(api POST "/api/cryptobot/wallets/${wid}/proposals" '{"kind":"REBALANCE","targetWeights":{"SOL":50},"counterAsset":"BONK","reasoningSummary":"demo: adversarial mint — BONK is not in the allowed asset list"}')"; split_status
  [[ "$STATUS" == "201" ]] || fail "mint-not-allowed propose: HTTP ${STATUS} ${BODY}"
  pid="$(printf '%s' "$BODY" | jget "['proposal']['id']")"; pstatus="$(printf '%s' "$BODY" | jget "['proposal']['status']")"
  rules="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin)['proposal']['policy']; print('; '.join(f\"{v['rule']}: {v['message']}\" for v in d.get('violations', [])))")"
  blocked_by="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin)['proposal']['policy']; print(','.join(v['rule'] for v in d.get('violations', [])))")"
  log "proposal ${pid}: ${pstatus}; blocked by [${blocked_by}]; ${rules}"
  [[ "$pstatus" == "BLOCKED_BY_POLICY" ]] || fail "expected BLOCKED_BY_POLICY for the BONK mint, got ${pstatus}: ${BODY}"
  [[ ",${blocked_by}," == *",ASSET_ALLOWLIST,"* ]] || fail "expected ASSET_ALLOWLIST among the violations, got [${blocked_by}]: ${rules}"
  printf '\033[1;31m[demo] BLOCKED_BY_POLICY\033[0m — mint not allowed: proposal %s wants BONK as the counter asset → ASSET_ALLOWLIST: %s\n' "$pid" "$rules" >&2
  EV2+=("adversarial mint (BONK) | proposal \`${pid}\` SOL → 50 % funded in BONK → **${pstatus}**; blocked by [${blocked_by}]; ${rules}")
  RESP="$(api POST "/api/cryptobot/proposals/${pid}/approve" '{"note":"demo: trying to approve a BONK-blocked proposal"}')"; split_status
  [[ "$STATUS" == "409" ]] || fail "approve of a policy-blocked proposal must be 409, got ${STATUS}: ${BODY}"
  log "approve → 409: $(printf '%s' "$BODY" | jget "['message']")"
  EV2+=("approve | **409** — $(printf '%s' "$BODY" | jget "['message']")")
  RESP="$(api GET "/api/cryptobot/proposals/${pid}/receipts")"; split_status
  kinds="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(' → '.join(x['body']['kind'] for x in d))")"
  MINT_RISK_RECEIPT="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); r=[x for x in d if x['body']['kind']=='RISK_DECISION']; print(r[-1]['receiptHash'] if r else '')")"
  if [[ -n "$MINT_RISK_RECEIPT" ]]; then
    RESP="$(api POST "/api/cryptobot/receipts/${MINT_RISK_RECEIPT}/verify" '{}')"; split_status
    log "receipts: ${kinds}; RISK_DECISION ${MINT_RISK_RECEIPT} verify valid=$(printf '%s' "$BODY" | jget "['valid']")"
    EV2+=("receipts | ${kinds}; RISK_DECISION \`${MINT_RISK_RECEIPT}\` — verify valid=$(printf '%s' "$BODY" | jget "['valid']"), signatureValid=$(printf '%s' "$BODY" | jget "['signatureValid']")")
  else
    EV2+=("receipts | ${kinds:-none}")
  fi
}

# KAN-607 — failure demo #2 (cheap): a receipt caught tampered at rest. `POST /receipts/{hash}/verify`
# (ReceiptsController) takes no body — it re-reads whatever is stored under receipt_hash and
# re-verifies THAT (KAN-597's body-based /receipts/verify is not built yet). So "1 byte altered in
# the body" is exercised the only way today's endpoint can observe it — the same pattern
# ReceiptTamperVerifyApiTest (KAN-604) uses at the repository level: the byte is flipped in what is
# PERSISTED, as if storage had corrupted it, and the same verify the API exposes today is asked to
# catch it. Needs direct DB access, so this runs only against the demo's own disposable Postgres
# (`--target local`); on `uat` it is SKIPPED, same as the chaos/price endpoints.
tampered_receipt_demo() {
  local hash="$1" before_valid after_valid hashok bodyok sigok
  [[ -n "$hash" ]] || { EV2+=("tampered receipt | SKIPPED — no receipt hash to tamper (the mint-not-allowed demo above produced none)"); return 0; }
  if [[ "$TARGET" != "local" ]]; then
    EV2+=("tampered receipt | SKIPPED — needs direct access to the demo's own Postgres, local target only by design (never touches a shared UAT/PROD database)")
    return 0
  fi
  step "receipt tampered at rest: flip 1 byte of \`${hash}\`'s stored canonical bytes, then verify again — docker exec into the demo's own Postgres"
  RESP="$(api POST "/api/cryptobot/receipts/${hash}/verify" '{}')"; split_status
  before_valid="$(printf '%s' "$BODY" | jget "['valid']")"
  [[ "$before_valid" == "True" ]] || fail "receipt ${hash} did not verify BEFORE tampering (valid=${before_valid}): ${BODY}"
  "${COMPOSE[@]}" exec -T demo-postgres psql -v ON_ERROR_STOP=1 -U "${CRYPTOBOT_DB_USER:-cryptobot}" -d life_engine_cryptobot -c \
    "UPDATE intelligence_receipt SET canonical = set_byte(canonical, length(canonical) / 2, (get_byte(canonical, length(canonical) / 2) # 1)) WHERE receipt_hash = '${hash}';" \
    >/dev/null || fail "could not flip a byte of the stored receipt ${hash} (demo-postgres unreachable?)"
  RESP="$(api POST "/api/cryptobot/receipts/${hash}/verify" '{}')"; split_status
  after_valid="$(printf '%s' "$BODY" | jget "['valid']")"; hashok="$(printf '%s' "$BODY" | jget "['hashMatchesCanonical']")"
  bodyok="$(printf '%s' "$BODY" | jget "['bodyMatchesCanonical']")"; sigok="$(printf '%s' "$BODY" | jget "['signatureValid']")"
  log "tampered receipt ${hash} verify: valid=${after_valid} hashMatchesCanonical=${hashok} bodyMatchesCanonical=${bodyok} signatureValid=${sigok}"
  [[ "$after_valid" == "False" ]] || fail "expected valid=false after flipping 1 byte of the stored receipt, got valid=${after_valid}: ${BODY}"
  printf '\033[1;31m[demo] TAMPER CAUGHT\033[0m — receipt %s: 1 byte flipped at rest → POST /receipts/%s/verify → valid=false (hashMatchesCanonical=%s, bodyMatchesCanonical=%s, signatureValid=%s: the Ed25519 signature alone does not prove the document)\n' \
    "$hash" "$hash" "$hashok" "$bodyok" "$sigok" >&2
  EV2+=("tampered receipt | \`${hash}\` — verify before: valid=true; 1 byte flipped in the stored \`canonical\` bytes (demo-postgres, same pattern as ReceiptTamperVerifyApiTest KAN-604); verify after: **valid=${after_valid}**, hashMatchesCanonical=${hashok}, bodyMatchesCanonical=${bodyok}, signatureValid=${sigok}")
}

# KAN-572 (HK-4): the second adversarial intent — nothing wrong with the trade, everything wrong with the
# price. The oracle needs ≥ 2 independent sources within 1 % of the median, younger than max-age, and no
# jump against the last consensus; the plan's price must agree with the fresh median. Here one source is
# tampered with (demo profile only), the real oracle refuses, the policy names the rule, the Decision
# Receipt (RISK_DECISION by policy-engine) is verified live — hash, signature, L1 re-execution — and sits
# in the lineage under the STRATEGY it validates. Then every source is made stale: PRICE_STALE.
act_price() {
  local wid="$1"
  RESP="$(api GET /api/cryptobot/demo/price)"; split_status
  if [[ "$STATUS" != "200" ]]; then
    if [[ "$TARGET" == "uat" ]]; then
      log "price injection not available on this target (HTTP ${STATUS}): CRYPTOBOT_CHAOS_ENABLED is demo-compose only, never UAT/PROD. The price intents run on the local target."
      EV2+=("adversarial price | SKIPPED — no /demo/price endpoint on ${TARGET} (HTTP ${STATUS}), by design (demo profile only)")
      PRICE_NOTE="price intents skipped"
      return 0
    fi
    fail "price endpoint returned HTTP ${STATUS} on the local demo stack: is CRYPTOBOT_CHAOS_ENABLED=true in docker-compose.demo.yml?"
  fi
  step "adversarial price #1: one oracle source (Pyth) is made to say SOL is worth −90 % — PUT /api/cryptobot/demo/price"
  RESP="$(api PUT /api/cryptobot/demo/price '{"asset":"SOL","source":"pyth-hermes","factor":0.1}')"; split_status
  [[ "$STATUS" == "200" ]] || fail "arm price override: HTTP ${STATUS} ${BODY}"
  PRICE_ARMED=1
  log "armed: $(printf '%s' "$BODY" | jget "['overrides']")"
  price_intent "$wid" PRICE_DEVIATION "pyth-hermes × 0.1 (one of ≥ 2 sources disagrees with the median beyond max-deviation)"

  step "adversarial price #2: every oracle source is made 15 minutes old — PUT /api/cryptobot/demo/price"
  RESP="$(api PUT /api/cryptobot/demo/price '{"asset":"SOL","source":"*","ageSeconds":900}')"; split_status
  [[ "$STATUS" == "200" ]] || fail "arm price override: HTTP ${STATUS} ${BODY}"
  price_intent "$wid" PRICE_STALE "every source observed 900 s ago (older than max-age: no fresh quorum)"

  RESP="$(api DELETE /api/cryptobot/demo/price)"; split_status
  [[ "$STATUS" == "200" ]] || fail "disarm price override: HTTP ${STATUS} ${BODY}"
  PRICE_ARMED=0
  log "price override disarmed: armed=$(printf '%s' "$BODY" | jget "['armed']")"
  EV2+=("price override | disarmed (\`DELETE /api/cryptobot/demo/price\`); the next intent (act 3) is priced by the real consensus again")
  PRICE_NOTE="PRICE_DEVIATION + PRICE_STALE blocked, receipts verified"
}

# price_intent <wallet-id> <expected-rule> <what was injected>
price_intent() {
  local wid="$1" expect="$2" injected="$3" pid pstatus rules blocked_by
  RESP="$(api POST "/api/cryptobot/wallets/${wid}/proposals" "{\"kind\":\"REBALANCE\",\"targetWeights\":{\"SOL\":50},\"reasoningSummary\":\"demo: adversarial price — ${expect}\"}")"; split_status
  [[ "$STATUS" == "201" ]] || fail "price intent propose: HTTP ${STATUS} ${BODY}"
  pid="$(printf '%s' "$BODY" | jget "['proposal']['id']")"; pstatus="$(printf '%s' "$BODY" | jget "['proposal']['status']")"
  rules="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin)['proposal']['policy']; print('; '.join(f\"{v['rule']}: {v['message']}\" for v in d.get('violations', [])))")"
  blocked_by="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin)['proposal']['policy']; print(','.join(v['rule'] for v in d.get('violations', [])))")"
  local oracle_sol; oracle_sol="$(printf '%s' "$BODY" | python3 -c "
import json,sys
d=json.load(sys.stdin)['proposal']['policy'].get('oracle') or {}
for a in d.get('assets', []):
    if a['asset']=='SOL':
        used='; '.join(f\"{o['source']}=\${o['priceUsd']}\" for o in a.get('used', []))
        rej='; '.join(f\"{o['observation']['source']}={o['reason']}\" for o in a.get('rejected', []))
        print(f\"refusals={a.get('refusals')} used=[{used}] rejected=[{rej}] limits(min={d['limits']['minSources']}, maxAge={d['limits']['maxAgeSeconds']}s, maxDev={d['limits']['maxDeviationBps']}bps)\")
")"
  log "proposal ${pid}: ${pstatus}; blocked by [${blocked_by}]; ${rules}"
  log "oracle saw: ${oracle_sol}"
  [[ "$pstatus" == "BLOCKED_BY_POLICY" ]] || fail "expected BLOCKED_BY_POLICY for the ${expect} intent, got ${pstatus}: ${BODY}"
  [[ ",${blocked_by}," == *",${expect},"* ]] || fail "expected rule ${expect} among the violations, got [${blocked_by}]: ${rules}"
  EV2+=("adversarial price (${expect}) | injected: ${injected} → proposal \`${pid}\` SOL → 50 % → **${pstatus}**; blocked by [${blocked_by}]")
  EV2+=("rule + message | ${rules}")
  EV2+=("what the oracle saw | ${oracle_sol}")
  # The Decision Receipt: the last RISK_DECISION of the proposal is the policy engine's; verify re-runs the engine.
  RESP="$(api GET "/api/cryptobot/proposals/${pid}/receipts")"; split_status
  local kinds dhash dparams
  kinds="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(' → '.join(x['body']['kind'] + ('(policy-engine)' if (x['body'].get('engine') or {}).get('id')=='policy-engine' else '') for x in d))")"
  dhash="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); r=[x for x in d if x['body']['kind']=='RISK_DECISION' and (x['body'].get('engine') or {}).get('id')=='policy-engine']; print(r[-1]['receiptHash'] if r else '')")"
  [[ -n "$dhash" ]] || fail "no Decision Receipt (RISK_DECISION by policy-engine) on proposal ${pid}: ${kinds}"
  dparams="$(printf '%s' "$BODY" | python3 -c "
import json,sys
d=json.load(sys.stdin); r=[x for x in d if x['receiptHash']=='${dhash}'][0]['body']; p=r.get('params',{})
print(f\"agent {r['agentId']} · engine {r['engine']['id']} {r['engine']['version']} H_R {r['engine']['weightsHash'][:23]}… · status {p.get('status')} · blockedBy {p.get('blockedBy')} · failedPredicates {p.get('failedPredicates')} · rule.${expect}: {p.get('rule.${expect}')} · oracle.SOL: {p.get('oracle.SOL')}\")
")"
  RESP="$(api POST "/api/cryptobot/receipts/${dhash}/verify" '{}')"; split_status
  local valid sigok repro reason engine
  valid="$(printf '%s' "$BODY" | jget "['valid']")"; sigok="$(printf '%s' "$BODY" | jget "['signatureValid']")"; repro="$(printf '%s' "$BODY" | jget "['reproduced']")"
  reason="$(printf '%s' "$BODY" | jget "['reproduction']['reason']")"; engine="$(printf '%s' "$BODY" | jget "['reproduction']['engineId']")"
  log "receipts: ${kinds}; Decision Receipt ${dhash} verify valid=${valid} signatureValid=${sigok} reproduced=${repro} (${engine}: ${reason})"
  [[ "$valid" == "True" && "$repro" == "True" ]] || fail "the Decision Receipt did not verify: valid=${valid} reproduced=${repro} ${BODY}"
  EV2+=("Decision Receipt | \`${dhash}\` — ${dparams}")
  EV2+=("receipt verify (live) | **valid=${valid}**, signatureValid=${sigok}, **reproduced=${repro}** (${engine} re-run on the stored (I, S): ${reason}); receipts: ${kinds}")
  # …and in the lineage (KAN-393): the decision under the STRATEGY it validates.
  RESP="$(api GET "/api/cryptobot/proposals/${pid}/lineage")"; split_status
  local lin; lin="$(printf '%s' "$BODY" | python3 -c "
import json,sys
d=json.load(sys.stdin); nodes=d.get('nodes') or d.get('receipts') or []; edges=d.get('edges') or []
mine=[e for e in edges if e.get('childHash')=='${dhash}' or e.get('from')=='${dhash}' or e.get('receiptHash')=='${dhash}']
print(f\"{len(nodes)} nodes, {len(edges)} edges; decision receipt present={'${dhash}' in json.dumps(d)}; its edges: \" + ', '.join(f\"{e.get('role')} → {(e.get('parentHash') or e.get('to') or '')[:16]}…\" for e in mine))
")"
  log "lineage: ${lin}"
  EV2+=("lineage (KAN-393) | ${lin}")
  # A blocked proposal cannot be approved.
  RESP="$(api POST "/api/cryptobot/proposals/${pid}/approve" '{"note":"demo: trying to approve a price-blocked proposal"}')"; split_status
  [[ "$STATUS" == "409" ]] || fail "approve of a price-blocked proposal must be 409, got ${STATUS}: ${BODY}"
  EV2+=("approve | **409** — $(printf '%s' "$BODY" | jget "['message']")")
}

# ---- act 4: evidence ------------------------------------------------------------------------------
act_evidence() {
  local receipt="${A1[RECEIPT_EXECUTION]:?}" parents
  step "the receipt DAG: parents of the EXECUTION receipt"
  RESP="$(api GET "/api/cryptobot/receipts/${receipt}")"; split_status
  [[ "$STATUS" == "200" ]] || fail "GET receipt: HTTP ${STATUS} ${BODY}"
  parents="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print('; '.join(f\"{e['role']} → {e['parentHash'][:23]}…\" for e in d['parents']))")"
  local chain="" ph pk
  for ph in $(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(' '.join(e['parentHash'] for e in d['parents']))"); do
    RESP="$(api GET "/api/cryptobot/receipts/${ph}")"; split_status
    pk="$(printf '%s' "$BODY" | jget "['receipt']['body']['kind']")"
    chain+="${pk} ← $(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(', '.join(f\"{e['role']} \" + e['parentHash'][:16] for e in d['parents']) or 'root')"); "
  done
  log "EXECUTION ${receipt} parents: ${parents}; grandparents: ${chain}"
  EV4+=("DAG | EXECUTION \`${receipt}\` ⇐ ${parents}")
  EV4+=("DAG (one level up) | ${chain}")

  step "Merkle anchor: batch the receipts waiting, sign the memo (signer, devnet only), broadcast, wait for finality"
  RESP="$(api POST "/api/cryptobot/anchors?wait=true" '{}')"; split_status
  [[ "$STATUS" == "200" ]] || fail "POST /anchors: HTTP ${STATUS} ${BODY}"
  local root tx astatus settled pending
  root="$(printf '%s' "$BODY" | jget "['anchored']['root']")"; tx="$(printf '%s' "$BODY" | jget "['anchored']['tx']")"; astatus="$(printf '%s' "$BODY" | jget "['anchored']['status']")"
  settled="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d.get('settled') or []))")"; pending="$(printf '%s' "$BODY" | jget "['pending']")"
  log "sweep: opened batch root=${root} tx=${tx} status=${astatus}; settled ${settled} earlier batches; ${pending} receipts still unanchored"
  EV4+=("anchor sweep | batch root \`${root}\` memo tx \`${tx}\` status ${astatus}; ${settled} earlier batch(es) settled; pending after ${pending}")

  step "the EXECUTION receipt's inclusion proof (POST /receipts/{hash}/verify → anchor.proofValid)"
  local i inc_status proof_ok inc_root inc_tx inc_slot proof_len
  for i in $(seq 1 15); do
    RESP="$(api POST "/api/cryptobot/receipts/${receipt}/verify" '{}')"; split_status
    inc_status="$(printf '%s' "$BODY" | jget "['anchor']['status']")"; proof_ok="$(printf '%s' "$BODY" | jget "['anchor']['proofValid']")"
    [[ "$inc_status" == "FINALIZED" && "$proof_ok" == "True" ]] && break
    log "anchor ${inc_status:-none} proofValid=${proof_ok:-?}; sweeping again (${i}/15)"
    sleep 6
    RESP="$(api POST "/api/cryptobot/anchors?wait=true" '{}')"; split_status
  done
  [[ "$inc_status" == "FINALIZED" && "$proof_ok" == "True" ]] || fail "the EXECUTION receipt is not in a finalized anchor (status ${inc_status}, proofValid ${proof_ok}): ${BODY}"
  inc_root="$(printf '%s' "$BODY" | jget "['anchor']['root']")"; inc_tx="$(printf '%s' "$BODY" | jget "['anchor']['tx']")"; inc_slot="$(printf '%s' "$BODY" | jget "['anchor']['slot']")"
  proof_len="$(printf '%s' "$BODY" | python3 -c "import json,sys; print(len(json.load(sys.stdin)['anchor']['proof']))")"
  local rvalid; rvalid="$(printf '%s' "$BODY" | jget "['valid']")"
  log "receipt verify: valid=${rvalid}; anchor FINALIZED root=${inc_root} tx=${inc_tx} slot=${inc_slot} proof=${proof_len} siblings proofValid=${proof_ok}"
  EV4+=("inclusion | EXECUTION receipt in batch \`${inc_root}\`: **proofValid=${proof_ok}** (${proof_len} Merkle siblings), memo tx \`${inc_tx}\` **FINALIZED** at slot ${inc_slot}; receipt verify valid=${rvalid}")
  EV4+=("explorer (anchor) | $(printf '%s' "$BODY" | jget "['anchor']['explorerUrl']")")

  step "the batch, verified: root recomputed from its receipts, every proof folded, memo parsed, transaction read back from the chain"
  RESP="$(api POST "/api/cryptobot/anchors/${inc_root}/verify" '{}')"; split_status
  [[ "$STATUS" == "200" ]] || fail "anchor verify: HTTP ${STATUS} ${BODY}"
  local v; v="$(printf '%s' "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); oc=d.get('onChain') or {}; print(f\"valid={d.get('valid')} rootMatches={d.get('rootMatches')} countMatches={d.get('countMatches')} proofsValid={d.get('proofsValid')} memoMatches={d.get('memoMatches')} onChain(found={oc.get('found')} memoMatches={oc.get('memoMatches')} slot={oc.get('slot')}) memo='{d.get('memo')}'\")")"
  log "anchor verify: ${v}"
  [[ "$v" == valid=True* ]] || fail "anchor verification failed: ${v}"
  EV4+=("anchor verify | ${v}")

  METRICS_MD="$({ curl -fsS -m 5 "${CURL_OPTS[@]}" "${BASE}/actuator/prometheus" 2>/dev/null || echo "actuator not reachable from here (403 behind the edge is expected in UAT)"; } \
    | grep -E '^(cryptobot_|trade_|policy_verdicts|policy_predicate|receipt_anchors|anchored_receipts|intelligence_receipts|dlq_size|validator_attestations|duplicate_trade|reconciliation_mismatch|actuator)' \
    | grep -v ' 0.0$' | sed -E 's/(application|commit|environment|service|version)="[^"]*",?//g; s/,\}/}/; s/\{\}//' | sort || true)"
  log "metrics: $(printf '%s' "$METRICS_MD" | wc -l) non-zero series captured"
}

# ---- main -----------------------------------------------------------------------------------------
mkdir -p "$RUN_DIR"
exec 3>&1 4>&2
exec > >(tee -a "$LOG") 2>&1
TEE_PID=$!
log "CryptoBot demo (KAN-575 HK-7) · ${TS} · target ${TARGET} · out ${RUN_DIR}"

act_begin "setup"
if [[ "$TARGET" == "local" ]]; then setup_local; else setup_uat; fi
if [[ "$DRY_RUN" -eq 1 ]]; then
  log "dry run — the plan:"
  log "  act 1 execute   ${HERE}/e2e-devnet.sh $([[ "$TARGET" == "local" ]] && echo --no-up || echo "--base-url ${BASE}") $([[ "$RPC_MODE" == "local" ]] && echo --local-validator) --env-file ${ENV_FILE} --sell-sol ${SELL_SOL} --token-env CRYPTOBOT_DEMO_TOKEN"
  log "  act 2 risk      adversarial intent SOL → 5 % → BLOCKED_BY_POLICY; approve/execute → 409; (KAN-607) mint into BONK → ASSET_ALLOWLIST; a RISK_DECISION receipt tampered at rest (1 byte, local target only) → verify valid=false; cooldown ${COOLDOWN_S}s; then (KAN-572) PUT /demo/price → SOL → 50 % blocked by PRICE_DEVIATION, then PRICE_STALE; Decision Receipts verified + lineage"
  log "  act 3 recovery  $([[ "$RECOVERY" -eq 1 ]] && echo "e2e-devnet.sh --chaos rpc-down (if the target has the chaos endpoint)" || echo "skipped (--no-recovery)")"
  log "  act 4 evidence  $([[ "$ANCHOR" -eq 1 ]] && echo "receipt DAG → POST /anchors?wait=true → inclusion proof → anchor verify → metrics" || echo "skipped (--no-anchor)")"
  log "  report          ${REPORT}"
  act_end PASS "dry run"
  exit 0
fi
act_end PASS "${RPC_LABEL}"

E2E_COMMON=(--env-file "$ENV_FILE" --token-env CRYPTOBOT_DEMO_TOKEN --sell-sol "$SELL_SOL")
if [[ "$TARGET" == "local" ]]; then E2E_COMMON+=(--no-up); [[ "$RPC_MODE" == "local" ]] && E2E_COMMON+=(--local-validator); else E2E_COMMON+=(--base-url "$BASE"); fi
# CRYPTOBOT_DEMO_CURL_OPTS is inherited by e2e-devnet.sh from the environment (uat target).

act_begin "execute — request → policy → approval → timelock → Solana → confirmed → EXECUTED → receipt"
"${HERE}/e2e-devnet.sh" "${E2E_COMMON[@]}" --evidence "${RUN_DIR}/act1-evidence.md" --summary "${RUN_DIR}/act1.env" \
  || fail "act 1 (e2e-devnet.sh) failed — see the steps above"
read_summary "${RUN_DIR}/act1.env" A1
act_end PASS "signature ${A1[SIGNATURE]:-?} ${A1[CONFIRMATION]:-?} · receipt ${A1[RECEIPT_EXECUTION]:-?} · mainnet ${A1[MAINNET]:-?}"

act_begin "risk — adversarial intents blocked (policy, mint, price), a tampered receipt caught, approve/execute 409, cooldown, Decision Receipts verified"
act_risk
act_end PASS "BLOCKED_BY_POLICY + mint ASSET_ALLOWLIST + tampered receipt + 409 + cooldown ${COOLDOWN_S}s; ${PRICE_NOTE:-price intents not run}"

act_begin "recovery — RPC down at broadcast → dead letter → requeue → idempotent retry → EXECUTED"
if [[ "$RECOVERY" -eq 0 ]]; then
  act_end SKIPPED "--no-recovery"
else
  RESP="$(api GET /api/cryptobot/demo/chaos)"; split_status
  if [[ "$STATUS" != "200" ]]; then
    if [[ "$TARGET" == "uat" ]]; then
      log "chaos endpoint not available on this target (HTTP ${STATUS}): CRYPTOBOT_CHAOS_ENABLED is demo-compose only, never UAT/PROD. Recovery is demonstrated on the local target."
      act_end SKIPPED "no chaos endpoint on ${TARGET} (HTTP ${STATUS}) — by design"
    else
      fail "chaos endpoint returned HTTP ${STATUS} on the local demo stack: is CRYPTOBOT_CHAOS_ENABLED=true in docker-compose.demo.yml?"
    fi
  else
    "${HERE}/e2e-devnet.sh" "${E2E_COMMON[@]}" --chaos rpc-down --evidence "${RUN_DIR}/act3-evidence.md" --summary "${RUN_DIR}/act3.env" \
      || fail "act 3 (e2e-devnet.sh --chaos rpc-down) failed — see the steps above"
    read_summary "${RUN_DIR}/act3.env" A3
    act_end PASS "dead letter ${A3[DEAD_LETTER_ID]:-?} → ${A3[REQUEUE_OUTCOME]:-?} → retries ${A3[RETRIES]:-?}; sig #1 ${A3[SIG1_ON_CHAIN]:-?}, sig #2 ${A3[SIG2_ON_CHAIN]:-?}; vault ${A3[VAULT_TXS_BEFORE]:-?}→${A3[VAULT_TXS_AFTER]:-?}"
  fi
fi

act_begin "evidence — receipt DAG, Merkle anchor on Solana (finalized), inclusion proof, metrics"
if [[ "$ANCHOR" -eq 0 ]]; then
  act_end SKIPPED "--no-anchor"
else
  act_evidence
  act_end PASS "anchor finalized, inclusion proof valid"
fi
exit 0
