#!/usr/bin/env bash
# shellcheck disable=SC2034  # TOKEN/CURL_OPTS/RESP are read by the helpers sourced from lib.sh
# The golden path: the whole Proof of Value story on Solana devnet, one command, then the links to verify it.
#
#   scripts/demo/hackathon.sh                 preflight → the 9-step story with a REAL CryptoBot operation → chain check → links
#   scripts/demo/hackathon.sh --check         preflight only: what is up, what is missing and how to fix it (spends nothing)
#   scripts/demo/hackathon.sh --setup         first time: devnet wallets + .env.demo (if missing), then the demo stack + UI
#   scripts/demo/hackathon.sh --dry-run       preflight + the plan of every step, nothing sent
#
#   --no-operation          skip the real CryptoBot operation (step 6): ~0.02 SOL instead of moving 21–40 % of the
#                           demo wallet to the demo's own vault
#   --min-sol N             abort unless the demo wallet holds ≥ N SOL (default 1.0; 0.1 with --no-operation)
#   --fresh                 abort unless the database holds no ValueEvent at all (use it before recording)
#   --task-id ID            neutral task id (default TASK-042); an id already on the database aborts the run
#   --expect-commit SHA     the commit the running service must report (default: HEAD of this checkout)
#   --expect-image ID       also require this image id (sha256:…) for the running service container
#   --acceptance-json F     a measured release-truth report (see "Acceptance")
#   --env-file F            default ./.env.demo · --out DIR  default ./out
#
# Preflight — before a single lamport moves; any FAIL aborts with the fix: tools · .env.demo · the RPC IS devnet (genesis
# hash, not a variable) · wallet balance · service, UI, signer, validator and Postgres healthy · the commit (and image)
# the service runs is the expected one · every endpoint the story calls answers · the database is clean (no tracker ids,
# the task id not used before).
#
# The story (pov-e2e.sh): 1 a human defines a task · 2 an agent implements it (commit, PR, image) · 3 acceptance: MERGED →
# BUILT → DEPLOYED → RUNNING → ACCEPTED · 4 the ValueEvent is signed and its Merkle root anchored on Solana · 5
# contributors paid on devnet · 6 CryptoBot executes a real operation: intent → policy → approval → independent
# validator → isolated signer → Solana · 7 a RevenueEvent (simulated economic result, labelled) shares value by
# Contribution Units · 8 historical distribution and the treasury read model · 9 reputation and the units ledger.
# Then every transaction of the run is asked to devnet (getSignatureStatuses): finalized and without error.
#
# Acceptance: with --acceptance-json, or RELEASE_TRUTH=<path to Life Engine's release-truth.sh> on the host that runs
# the deployed service, the five stages come from a MEASURED verdict. Without either (a fresh clone) they are DECLARED
# for the demo task and the run says so in red.
#
# A wrapper: the work is done by wallet-devnet.sh, docker-compose.demo.yml and pov-e2e.sh. Devnet only; no secret is
# printed. Exit: 0 pass · 1 preflight or a step failed · 3 a secret reached the report/log · 4 a tx is not finalized.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "${HERE}/../.." && pwd)"
# shellcheck source=lib.sh
source "${HERE}/lib.sh"

DEVNET_GENESIS="EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG"
MODE=run; OPERATION=1; TASK_ID="TASK-042"; TASK="Improve CryptoBot opportunity detection"; ACCEPTANCE_JSON=""
ENV_FILE="${PROJECT}/.env.demo"; OUT_DIR="${PROJECT}/out"; MIN_SOL=""; FRESH=0; EXPECT_COMMIT=""; EXPECT_IMAGE=""
while (( $# )); do
  case "$1" in
    --check) MODE=check; shift ;;
    --setup) MODE=setup; shift ;;
    --dry-run) MODE=dry-run; shift ;;
    --no-operation) OPERATION=0; shift ;;
    --fresh) FRESH=1; shift ;;
    --min-sol) MIN_SOL="${2:?--min-sol needs a value}"; shift 2 ;;
    --task-id) TASK_ID="${2:?--task-id needs a value}"; shift 2 ;;
    --expect-commit) EXPECT_COMMIT="${2:?--expect-commit needs a sha}"; shift 2 ;;
    --expect-image) EXPECT_IMAGE="${2:?--expect-image needs an image id}"; shift 2 ;;
    --acceptance-json) ACCEPTANCE_JSON="${2:?--acceptance-json needs a file}"; shift 2 ;;
    --env-file) ENV_FILE="${2:?--env-file needs a file}"; shift 2 ;;
    --out) OUT_DIR="${2:?--out needs a directory}"; shift 2 ;;
    -h|--help) sed -n '3,37p' "$0"; exit 0 ;;
    *) fail "unknown argument: $1 (see --help)" ;;
  esac
done
[[ -z "$MIN_SOL" || "$MIN_SOL" =~ ^[0-9]+([.][0-9]{1,9})?$ ]] || fail "--min-sol must be a number of SOL (e.g. 1.5)"
[[ -z "$EXPECT_COMMIT" || "$EXPECT_COMMIT" =~ ^[0-9a-f]{7,40}$ ]] || fail "--expect-commit must be a hex sha"
[[ -z "$EXPECT_IMAGE" || "$EXPECT_IMAGE" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "--expect-image must be sha256:<64 hex>"
[[ "$TASK_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$ && "${TASK_ID^^}" != KAN-* ]] || fail "--task-id must be a neutral id such as TASK-042"
[[ -z "$ACCEPTANCE_JSON" || -f "$ACCEPTANCE_JSON" ]] || fail "no ${ACCEPTANCE_JSON}"
[[ -n "$MIN_SOL" ]] || MIN_SOL=$( (( OPERATION )) && echo 1.0 || echo 0.1 )

env_value() { [[ -f "$ENV_FILE" ]] && sed -n "s/^$1=//p" "$ENV_FILE" | tail -1 || true; }   # public values only
STACK="${CRYPTOBOT_DEMO_PROJECT:-cryptobot-demo}"   # the compose's container_name prefix
compose() {
  local args=(docker compose)
  [[ -n "${CRYPTOBOT_DEMO_PROJECT:-}" ]] && args+=(-p "$CRYPTOBOT_DEMO_PROJECT")
  "${args[@]}" -f "${PROJECT}/docker-compose.demo.yml" --env-file "$ENV_FILE" "$@"
}
UI_CONTEXT="${CRYPTOBOT_UI_CONTEXT:-${PROJECT}/../cryptobot-ui}"
verdict() { printf '\n\033[1;%sm%s\033[0m\n' "$1" "$2"; }

# ---- setup (first time) ------------------------------------------------------------------------------
if [[ "$MODE" == setup ]]; then
  need docker; need curl; need python3; need git
  if [[ -f "$ENV_FILE" ]]; then log "${ENV_FILE} exists: keys and secrets are kept"
  else log "devnet wallets + ${ENV_FILE} (never committed) + airdrop"; "${HERE}/wallet-devnet.sh"; fi
  [[ -d "$UI_CONTEXT" ]] || fail "the UI is built from ${UI_CONTEXT}: git clone https://github.com/sebdev89/life-engine-cryptobot-ui \"${UI_CONTEXT}\" (or set CRYPTOBOT_UI_CONTEXT)"
  # The build context has no .git: the commit enters as a build-arg, so /actuator/info can say what is running.
  log "building the demo stack + UI at commit $(git -C "$PROJECT" rev-parse --short=7 HEAD) (the first build takes a few minutes)"
  export CRYPTOBOT_UI_CONTEXT="$UI_CONTEXT"
  compose --profile ui build \
    --build-arg "GIT_COMMIT=$(git -C "$PROJECT" rev-parse HEAD)" \
    --build-arg "GIT_BRANCH=$(git -C "$PROJECT" rev-parse --abbrev-ref HEAD)" \
    --build-arg "GIT_COMMIT_TIME=$(git -C "$PROJECT" log -1 --format=%cI)"
  compose --profile ui up -d
  log "setup done — next: scripts/demo/hackathon.sh --check, then scripts/demo/hackathon.sh"
  exit 0
fi

# ---- preflight: nothing is sent until every line is OK -----------------------------------------------
step "PREFLIGHT — nothing moves until every line is OK"
FAILS=()
pf_ok()   { printf '  \033[1;32mOK\033[0m    %s\n' "$*" >&2; }
pf_warn() { printf '  \033[1;33mWARN\033[0m  %s\n' "$*" >&2; }
pf_fail() { printf '  \033[1;31mFAIL\033[0m  %s\n' "$*" >&2; FAILS+=("$*"); }
for t in docker curl python3 git; do
  if command -v "$t" >/dev/null 2>&1; then pf_ok "tool ${t}"; else pf_fail "tool ${t} is missing"; fi
done
if [[ -f "$ENV_FILE" ]]; then pf_ok "demo keys and settings: ${ENV_FILE}"
else pf_fail "no ${ENV_FILE} → scripts/demo/hackathon.sh --setup"; fi

# network: the genesis hash of the RPC the stack uses, not its name
RPC_URL="$(pov_rpc_url "$ENV_FILE")"
genesis="$(rpc "$RPC_URL" getGenesisHash '[]' 2>/dev/null | jget "['result']" || true)"
if [[ "$genesis" == "$DEVNET_GENESIS" ]]; then pf_ok "network: devnet (genesis ${genesis:0:8}… from ${RPC_URL})"
elif [[ -z "$genesis" ]]; then pf_fail "network: ${RPC_URL} does not answer getGenesisHash → check the RPC (CRYPTOBOT_SOLANA_DEVNET_RPC) or try later"
else pf_fail "network: ${RPC_URL} is NOT devnet (genesis ${genesis:0:8}…) → this path proves on devnet only; fix CRYPTOBOT_SOLANA_DEVNET_RPC"; fi

# wallet
WALLET="$(env_value DEMO_WALLET_ADDRESS)"
if [[ -z "$WALLET" ]]; then pf_fail "no DEMO_WALLET_ADDRESS in ${ENV_FILE} → scripts/demo/wallet-devnet.sh --no-airdrop"
elif [[ "$genesis" == "$DEVNET_GENESIS" ]]; then
  lamports="$(balance_lamports "$RPC_URL" "$WALLET" 2>/dev/null || echo 0)"
  if python3 -c "import sys; sys.exit(0 if int('${lamports:-0}' or 0) >= round(${MIN_SOL}*1e9) else 1)"; then
    pf_ok "wallet: ${WALLET} holds $(python3 -c "print('%.4f' % (int('${lamports:-0}')/1e9))") SOL (minimum ${MIN_SOL})"
  else
    pf_fail "wallet: ${WALLET} holds $(python3 -c "print('%.4f' % (int('${lamports:-0}' or 0)/1e9))") SOL, below --min-sol ${MIN_SOL} → https://faucet.solana.com (devnet)"
  fi
fi

# containers: signer, validator, Postgres (private network, no host port) — their own healthchecks
for c in signer validator postgres; do
  h="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${STACK}-${c}" 2>/dev/null || true)"
  if [[ "$h" == healthy ]]; then pf_ok "${c}: ${STACK}-${c} healthy"
  else pf_fail "${c}: ${STACK}-${c} is ${h:-not running} → scripts/demo/hackathon.sh --setup (or set CRYPTOBOT_DEMO_PROJECT to your stack's name)"; fi
done

# service + UI
port="$(env_value CRYPTOBOT_DEMO_PORT)"; BASE="http://127.0.0.1:${CRYPTOBOT_DEMO_PORT:-${port:-8091}}"
ui_port="$(env_value UI_PORT)"; UI_BASE="http://127.0.0.1:${UI_PORT:-${ui_port:-4204}}"
SERVICE_UP=0
if curl -fsS -m 5 "${BASE}/api/cryptobot/health" 2>/dev/null | grep -q '"UP"'; then pf_ok "service: UP at ${BASE}"; SERVICE_UP=1
else pf_fail "service: not reachable at ${BASE} → scripts/demo/hackathon.sh --setup"; fi
ui_code="$(curl -s -o /dev/null -m 5 -w '%{http_code}' "${UI_BASE}/" 2>/dev/null || true)"
if [[ "$ui_code" == 200 ]]; then pf_ok "UI: ${UI_BASE} answers 200"
else pf_fail "UI: ${UI_BASE} answers ${ui_code:-nothing} → scripts/demo/hackathon.sh --setup (the UI is built from ../cryptobot-ui)"; fi

# what is running: commit from /actuator/info, image id from docker — against what is declared
if (( SERVICE_UP )); then
  want="${EXPECT_COMMIT:-$(git -C "$PROJECT" rev-parse --short=7 HEAD 2>/dev/null || true)}"
  running="$(curl -fsS -m 5 "${BASE}/actuator/info" 2>/dev/null | jget "['git']['commit']['id']" || true)"
  if [[ -z "$running" ]]; then pf_fail "build: the service does not report its commit (built without it) → scripts/demo/hackathon.sh --setup rebuilds it with the commit"
  elif [[ -n "$want" && ( "$want" == "$running"* || "$running" == "$want"* ) ]]; then pf_ok "build: service runs commit ${running} (expected ${want:0:7})"
  else pf_fail "build: service runs commit ${running}, expected ${want:0:7} → rebuild (scripts/demo/hackathon.sh --setup) or pass --expect-commit ${running} if that is deliberate"; fi
  image="$(docker inspect --format '{{.Image}}' "${STACK}-service" 2>/dev/null || true)"
  if [[ -n "$EXPECT_IMAGE" ]]; then
    if [[ "$image" == "$EXPECT_IMAGE" ]]; then pf_ok "image: ${image:0:19}… is the expected one"
    else pf_fail "image: ${STACK}-service runs ${image:-unknown}, expected ${EXPECT_IMAGE}"; fi
  elif [[ -n "$image" ]]; then pf_ok "image: ${image:0:19}… (pass --expect-image to pin it)"; fi
fi

# endpoints the story calls + database state (operator token minted locally from .env.demo, never printed)
if (( SERVICE_UP )) && [[ -f "$ENV_FILE" ]]; then
  TOKEN="${CRYPTOBOT_DEMO_TOKEN:-$(demo_token "$ENV_FILE")}"; CURL_OPTS=()
  bad=()
  for p in '/api/cryptobot/value-events?limit=100' '/api/cryptobot/revenue-events?limit=1' '/api/cryptobot/units/ledger?groupBy=identity' \
           '/api/cryptobot/wallets' '/api/cryptobot/proposals?limit=1'; do
    RESP="$(api GET "$p")"; split_status
    [[ "$STATUS" == 200 ]] || bad+=("${p%%\?*} → ${STATUS}")
    [[ "$p" == *value-events* ]] && EVENTS="$BODY"
  done
  if (( ${#bad[@]} == 0 )); then pf_ok "endpoints: value-events, revenue-events, units ledger, wallets, proposals answer 200"
  else pf_fail "endpoints: ${bad[*]} → the service is not the Proof of Value build, or the token is refused (JWT_SECRET of ${ENV_FILE})"; fi
  if [[ -n "${EVENTS:-}" ]]; then
    state="$(printf '%s' "$EVENTS" | python3 -c '
import json, re, sys
task = sys.argv[1]
d = json.load(sys.stdin); items = d if isinstance(d, list) else d.get("items", [])
tracker = sum(1 for i in items if re.search(r"KAN-\d", (i.get("taskId") or "") + " " + (i.get("title") or ""), re.I))
same = sum(1 for i in items if i.get("taskId") == task)
print(len(items), tracker, same)' "$TASK_ID" 2>/dev/null || echo "? ? ?")"
    read -r n_all n_tracker n_same <<<"$state"
    if [[ "$n_all" == "?" ]]; then pf_fail "database: could not read the ValueEvents"
    elif (( n_tracker > 0 )); then pf_fail "database: ${n_tracker} old ValueEvent(s) carry internal tracker ids → use a fresh stack (CRYPTOBOT_DEMO_PROJECT=<new name> scripts/demo/hackathon.sh --setup)"
    elif (( n_same > 0 )); then pf_fail "database: ${TASK_ID} was already used here — its payouts would be reused, not new → --task-id <another id>, or a fresh stack"
    elif (( FRESH && n_all > 0 )); then pf_fail "database: ${n_all} ValueEvent(s) already recorded and --fresh asks for none → a fresh stack (new CRYPTOBOT_DEMO_PROJECT)"
    elif (( n_all > 0 )); then pf_ok "database: ${TASK_ID} not used yet (${n_all} earlier ValueEvent(s) of other tasks; --fresh to require none)"
    else pf_ok "database: clean (no ValueEvent yet)"; fi
  fi
fi

# acceptance source
RT_TMP=""; ACC_MODE=declared
if [[ -n "$ACCEPTANCE_JSON" ]]; then ACC_MODE=measured; pf_ok "acceptance: measured report ${ACCEPTANCE_JSON}"
elif [[ -n "${RELEASE_TRUTH:-}" && -x "${RELEASE_TRUTH}" ]]; then
  RT_TMP="$(mktemp)"
  if "${RELEASE_TRUTH}" uat cryptobot --json > "$RT_TMP" 2>/dev/null; then
    ACC_MODE=measured; ACCEPTANCE_JSON="$RT_TMP"; pf_ok "acceptance: measured now by release-truth (every field PASS)"
  else pf_warn "acceptance: release-truth did not pass now → the five stages will be DECLARED, in red"; fi
else
  pf_warn "acceptance: no measured report on this machine → the five stages are DECLARED for the demo task (in red)"
fi
if (( OPERATION )); then pf_ok "story: 9 steps with a REAL CryptoBot operation (moves 21–40 % of the demo wallet to the demo's own vault)"
else pf_warn "story: --no-operation — step 6 skipped, the revenue source is SIMULATED (~0.02 SOL spent)"; fi

if (( ${#FAILS[@]} )); then
  verdict 31 "HACKATHON DEMO — FAIL at preflight (${#FAILS[@]} check(s); nothing was sent, no SOL moved)"
  for f in "${FAILS[@]}"; do printf '  - %s\n' "$f"; done
  [[ -n "$RT_TMP" ]] && rm -f "$RT_TMP"
  exit 1
fi
if [[ "$MODE" == check ]]; then log "preflight passed — run: scripts/demo/hackathon.sh$( (( OPERATION )) || echo ' --no-operation')"; exit 0; fi

# ---- the story (pov-e2e.sh, unchanged) ---------------------------------------------------------------
args=(--task "$TASK" --task-id "$TASK_ID" --env-file "$ENV_FILE" --out "$OUT_DIR")
if [[ "$ACC_MODE" == measured ]]; then args+=(--acceptance-json "$ACCEPTANCE_JSON")
else
  # Declared acceptance: the artifact is what THIS stack runs — the commit /actuator/info reports and the local image id —
  # resolved without credentials (the published images are private; a fresh clone has no access to them).
  slug="$(git -C "$PROJECT" remote get-url origin 2>/dev/null | sed -E 's#^(https://[^/]+/|git@[^:]+:)##; s#\.git$##' || true)"
  [[ "$slug" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] || slug="sebdev89/life-engine-cryptobot-service"
  commit="$(git -C "$PROJECT" rev-parse --verify -q "${running}^{commit}" 2>/dev/null || echo "$running")"
  pr="$(curl -fsS -m 10 -H 'Accept: application/vnd.github+json' "https://api.github.com/repos/${slug}/commits/${commit}/pulls" 2>/dev/null \
        | jget "[0]['html_url']" || true)"
  [[ "$pr" =~ ^https:// ]] || pr="https://github.com/${slug}/commit/${commit}"
  args+=(--assume-accepted --commit "$commit" --pr "$pr")
  [[ "$image" =~ ^sha256:[0-9a-f]{64}$ ]] && args+=(--image-digest "$image")
fi
(( OPERATION )) || args+=(--skip-op)
[[ "$MODE" == dry-run ]] && args+=(--dry-run)
mkdir -p "$OUT_DIR"; MARK="$(mktemp)"
cleanup() { rm -f "$MARK"; [[ -z "$RT_TMP" ]] || rm -f "$RT_TMP"; }
trap cleanup EXIT
rc=0; "${HERE}/pov-e2e.sh" "${args[@]}" || rc=$?
[[ "$MODE" == dry-run ]] && exit "$rc"
REPORT="$(find "$OUT_DIR" -maxdepth 1 -name 'pov-e2e-*.md' -newer "$MARK" -print 2>/dev/null | sort | tail -1)"
if (( rc != 0 )); then
  why="$( [[ -n "$REPORT" ]] && sed -n 's/^- result: FAILED at //p' "$REPORT" | head -1 )"
  (( rc == 3 )) && why="a secret value reached the report or the log (pov-e2e.sh secrets scan)"
  verdict 31 "HACKATHON DEMO — FAIL at ${why:-the story (exit ${rc})}"
  [[ -n "$REPORT" ]] && printf 'Report:           %s\n' "$REPORT"
  exit "$rc"
fi
[[ -n "$REPORT" ]] || { verdict 31 "HACKATHON DEMO — FAIL: pov-e2e.sh passed but wrote no report under ${OUT_DIR}"; exit 1; }

# ---- verify on Solana: every transaction of the report, asked to devnet, then the jury's summary -----
step "VERIFY ON SOLANA — every transaction of this run, asked to devnet"
crc=0
python3 - "$REPORT" "$RPC_URL" "$OPERATION" <<'PY' || crc=$?
import json, re, sys, urllib.request
report, rpc_url, operation = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
text = open(report, encoding="utf-8").read()
SIG = r"([1-9A-HJ-NP-Za-km-z]{64,90})"
X = "https://explorer.solana.com/tx/%s?cluster=devnet"
rows, seen, found = [], set(), {}
def add(key, label, sig):
    found.setdefault(key, sig)
    if sig not in seen:
        seen.add(sig); rows.append((label, sig))
for line in text.splitlines():
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    if len(cells) < 3 or not cells[0][:1].isdigit():
        continue
    step, what, value = cells[0], cells[1], "|".join(cells[2:])
    m = None
    if what == "value event":
        m = re.search(r"`([0-9a-f-]{36})`", value); m and found.setdefault("ve", m.group(1))
    elif what == "anchor":
        m = re.search(r"tx \[`" + SIG + r"`\]", value); m and add("ve_anchor", "ValueEvent anchor (Merkle root memo)", m.group(1))
        v = re.search(r"proof verified=(\w+)", value); v and found.setdefault("verified", v.group(1).lower())
    elif what == "payout":
        m = re.search(r"\[`" + SIG + r"`\]", value)
        m and add("payout", ("reward payout → " if step == "5" else "revenue payout → ") + value.split()[0], m.group(1))
    elif what == "operation":
        m = re.search(r"tx \[`" + SIG + r"`\]", value); m and add("op", "CryptoBot operation", m.group(1))
    elif what == "receipt":
        m = re.search(r"anchored in `" + SIG + "`", value); m and add("rev_anchor", "RevenueEvent anchor (Merkle root memo)", m.group(1))
if not rows:
    print("  no transaction found in the report"); sys.exit(4)
req = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "getSignatureStatuses",
                  "params": [[s for _, s in rows], {"searchTransactionHistory": True}]}).encode()
try:
    res = json.load(urllib.request.urlopen(urllib.request.Request(rpc_url, req, {"content-type": "application/json"}), timeout=30))
    statuses = res.get("result", {}).get("value") or []
except Exception as e:  # network trouble: say it in one line, no stack trace
    print("  devnet RPC did not answer getSignatureStatuses: %s" % type(e).__name__); statuses = []
good = 0
for i, (label, sig) in enumerate(rows):
    st = statuses[i] if i < len(statuses) else None
    ok = bool(st) and st.get("confirmationStatus") == "finalized" and st.get("err") is None
    good += ok
    state = "finalized" if ok else ((st or {}).get("confirmationStatus") or "not found") + ("" if not st or st.get("err") is None else " + error")
    print("  %-40s %-10s %s" % (label, state, X % sig))
print("  %d/%d transactions finalized on devnet without error" % (good, len(rows)))
passed = good == len(rows) and found.get("verified") == "true"
steps = "9/9" if operation else "8/9 (step 6 skipped: --no-operation)"
print()
print("HACKATHON DEMO — %s %s" % ("PASS" if passed else "FAIL at chain verification:", steps if passed else "%d/%d finalized, proof verified=%s" % (good, len(rows), found.get("verified", "?"))))
print("Operation:        %s" % (X % found["op"] if "op" in found else "skipped (--no-operation)"))
print("ValueEvent:       %s" % found.get("ve", "?"))
print("AcceptanceProof:  %s" % (X % found["ve_anchor"] if "ve_anchor" in found else "?"))
print("RevenueEvent:     %s" % (X % found["rev_anchor"] if "rev_anchor" in found else "?"))
print("Proof:            verified=%s" % found.get("verified", "?"))
print("Report:           %s" % report)
sys.exit(0 if passed else 4)
PY
exit "$crc"
