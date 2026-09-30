#!/usr/bin/env bash
# shellcheck disable=SC2034  # TOKEN/BASE/CURL_OPTS are read by the helpers sourced from lib.sh
# Proof of Value — the final demo, end to end, against a running stack (devnet), one block per step, with a report.
#
#   scripts/demo/pov-e2e.sh --task "<title>" --task-id <TASK-042>
#                           [--commit <sha>] [--pr <url>] [--image-digest <sha256:…>] [--compute-json <file>]
#                           [--acceptance-json <release-truth.json> | --assume-accepted]
#                           [--revenue-lamports N] [--skip-op] [--sell-sol N]
#                           [--env-file <f>] [--base-url <url>] [--ui-base <url>] [--out <dir>] [--dry-run]
#
#   STEP 1/9 Human defines task       --task / --task-id (a neutral id such as TASK-042; tracker ids are refused)
#   STEP 2/9 DevAgent implements      --commit/--pr/--image-digest (default, via `gh`: the image release-truth measured
#                                     and its commit, else HEAD of origin/main and its GHCR image sha-<commit>;
#                                     the PR of that commit); sebas SPECIFIER+ARCHITECT, dev-agent-17 IMPLEMENTER,
#                                     review-agent-3 REVIEWER, compute-node-8 COMPUTE_PROVIDER (its receipt from
#                                     --compute-json, else values marked "estimated"), assets strategy-knowledge@3 and
#                                     production-acceptance-model@1
#   STEP 3/9 Acceptance               MERGED→BUILT→DEPLOYED→RUNNING→ACCEPTED mapped from `release-truth.sh uat cryptobot
#                                     --json` (--acceptance-json): commit_sha+repository → MERGED · image_digest → BUILT ·
#                                     deployment_revision+effective_config_hash → DEPLOYED · health → RUNNING ·
#                                     functional_acceptance → ACCEPTED (a stage is true only if all its fields are PASS);
#                                     source release-truth, environment <env>-k8s. Without it, --assume-accepted asserts
#                                     the five stages by hand (source manual, printed in red); with neither, it refuses.
#   STEP 4/9 ValueEvent on Solana     POST /value-events?anchor=true → id, receiptHash, root, tx, explorer; GET /proof
#   STEP 5/9 Immediate payout         POST /value-events/{id}/distribute?anchor=true; payouts + wallet balances before/after
#   STEP 6/9 CryptoBot uses it        ONE real CryptoBot operation on devnet — e2e-devnet.sh (act 1 of run.sh) against the
#                                     same service: intent → policy → approve → execute → EXECUTED. It moves --sell-sol SOL
#                                     (clamped to 21–40 % of the demo wallet) to the vault. --skip-op spends nothing.
#   STEP 7/9 RevenueEvent             POST /revenue-events?anchor=true linked to the ValueEvent; source PROPOSAL <step 6>
#                                     (SIMULATED with --skip-op); --revenue-lamports (default 50 000 000), simulated=true
#   STEP 8/9 Historical distribution  CONFIRMED lamports per contributor over every distribution + revenue event; treasury
#   STEP 9/9 Reputation + units       GET /identities/{id} of each identity; GET /units/ledger?groupBy=identity
#   Final screen: VALUE GENERATED <x> SOL (simulated: yes/no) · ATTRIBUTION. Report: <out>/pov-e2e-<ts>.md (+ .log).
#
# Every step prints the UI screen that shows it (--ui-base, default http://127.0.0.1:${UI_PORT:-4204}; open it signed in
# with scripts/demo/ui-url.sh --path <route>). Nothing is invented: what is not measured is labelled estimated/SIMULATED.
# Auth as pov-v1.sh: a 1 h HS256 demo token from JWT_SECRET of the env file (never printed), or --base-url with
# CRYPTOBOT_DEMO_TOKEN. Exit: 0 pass · 1 a step failed (the report is still written) · 3 a secret reached the report/log.
set -euo pipefail
# (Proof of Value V9. Kept below the --help range: tracker ids stay out of the demo output.)
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "${HERE}/../.." && pwd)"
# shellcheck source=lib.sh
source "${HERE}/lib.sh"

TASK="Improve CryptoBot opportunity detection"; TASK_ID="TASK-042"; COMMIT=""; PR_URL=""; IMAGE_DIGEST=""; COMPUTE_JSON=""
ACCEPTANCE_JSON=""; ASSUME_ACCEPTED=0; REVENUE_LAMPORTS=50000000; SKIP_OP=0; SELL_SOL=1
ENV_FILE="${PROJECT}/.env.demo"; BASE=""; UI_BASE=""; OUT_DIR="${PROJECT}/out"; DRY_RUN=0; MEASURED_DIGEST=""
while (( $# )); do
  case "$1" in
    --task) TASK="$2"; shift 2 ;;
    --task-id) TASK_ID="$2"; shift 2 ;;
    --commit) COMMIT="$2"; shift 2 ;;
    --pr) PR_URL="$2"; shift 2 ;;
    --image-digest) IMAGE_DIGEST="$2"; shift 2 ;;
    --compute-json) COMPUTE_JSON="$2"; shift 2 ;;
    --acceptance-json) ACCEPTANCE_JSON="$2"; shift 2 ;;
    --assume-accepted) ASSUME_ACCEPTED=1; shift ;;
    --revenue-lamports) REVENUE_LAMPORTS="$2"; shift 2 ;;
    --skip-op) SKIP_OP=1; shift ;;
    --sell-sol) SELL_SOL="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --base-url) BASE="${2%/}"; shift 2 ;;
    --ui-base) UI_BASE="${2%/}"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '3,38p' "$0"; exit 0 ;;
    *) fail "unknown argument: $1 (see --help)" ;;
  esac
done
need curl; need python3

# ---- validation (before anything is sent) ---------------------------------------------------------
[[ "$TASK_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,63}$ ]] || fail "--task-id must be a short reference such as TASK-042"
if [[ "${TASK_ID^^}" == KAN-* || "${TASK^^}" == *KAN-[0-9]* ]]; then
  fail "--task/--task-id: use a neutral id (e.g. TASK-042) — tracker ids never appear in the demo output"
fi
[[ -n "$TASK" && ${#TASK} -le 200 ]] || fail "--task: 1–200 characters"
[[ "$REVENUE_LAMPORTS" =~ ^[1-9][0-9]{0,11}$ ]] || fail "--revenue-lamports must be a positive integer (lamports)"
[[ -z "$COMMIT" || "$COMMIT" =~ ^[0-9a-f]{7,64}$ ]] || fail "--commit must be a hex sha"
[[ -z "$PR_URL" || "$PR_URL" =~ ^https?://[^[:space:]]{1,290}$ ]] || fail "--pr must be an http(s) URL"
[[ -z "$IMAGE_DIGEST" || "$IMAGE_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "--image-digest must be sha256:<64 hex>"
if [[ -n "$ACCEPTANCE_JSON" && "$ASSUME_ACCEPTED" -eq 1 ]]; then fail "--acceptance-json and --assume-accepted are exclusive"; fi
if [[ -z "$ACCEPTANCE_JSON" && "$ASSUME_ACCEPTED" -eq 0 ]]; then
  fail "no acceptance: pass --acceptance-json <file> (deploy: deployments/scripts/release-truth.sh uat cryptobot --json > file) or --assume-accepted"
fi
[[ -z "$ACCEPTANCE_JSON" || -f "$ACCEPTANCE_JSON" ]] || fail "no ${ACCEPTANCE_JSON}"
[[ -z "$COMPUTE_JSON" || -f "$COMPUTE_JSON" ]] || fail "no ${COMPUTE_JSON}"

CURL_OPTS=()
env_value() { [[ -f "$ENV_FILE" ]] && sed -n "s/^$1=//p" "$ENV_FILE" | tail -1 || true; }
if [[ -z "$BASE" ]]; then
  [[ -f "$ENV_FILE" ]] || fail "no ${ENV_FILE}: run scripts/demo/run.sh --keep first, or pass --base-url with CRYPTOBOT_DEMO_TOKEN"
  port="$(env_value CRYPTOBOT_DEMO_PORT)"
  BASE="http://127.0.0.1:${CRYPTOBOT_DEMO_PORT:-${port:-8091}}"
fi
if [[ -z "$UI_BASE" ]]; then ui_port="$(env_value UI_PORT)"; UI_BASE="http://127.0.0.1:${UI_PORT:-${ui_port:-4204}}"; fi

# ---- acceptance: release-truth verdicts → the five stages -----------------------------------------
# acceptance_from_release_truth <file>: JSON {stages, fields, environment, observedAt, sha256} — the mapping is the table of --help.
acceptance_from_release_truth() {
  python3 - "$1" <<'PY'
import hashlib, json, re, sys
raw = open(sys.argv[1], "rb").read()
d = json.loads(raw)
f = d.get("fields") or {}
MAP = [("MERGED", ["commit_sha", "repository"]), ("BUILT", ["image_digest"]),
       ("DEPLOYED", ["deployment_revision", "effective_config_hash"]), ("RUNNING", ["health"]),
       ("ACCEPTED", ["functional_acceptance"])]
stages, detail = {}, {}
for stage, names in MAP:
    verdicts = {n: (f.get(n) or {}).get("verdict", "MISSING") for n in names}
    stages[stage] = all(v == "PASS" for v in verdicts.values())
    detail[stage] = " ".join("%s=%s" % kv for kv in verdicts.items())
env = d.get("environment") or "unknown"
if str(d.get("type", "")).startswith("k8s") and not env.endswith("-k8s"):
    env += "-k8s"
print(json.dumps({"stages": stages, "detail": detail, "environment": env[:32], "workload": d.get("workload"),
                  "observedAt": (d.get("evidence") or {}).get("observed_at"),
                  "commitNote": (f.get("commit_sha") or {}).get("note", ""),
                  "measuredDigest": (re.findall(r"sha256:[0-9a-f]{64}", (f.get("image_digest") or {}).get("note", "")) or [""])[0],
                  "measuredCommit": (re.findall(r"commit=([0-9a-f]{7,40})", (f.get("commit_sha") or {}).get("note", "")) or [""])[0],
                  "sha256": "sha256:" + hashlib.sha256(raw).hexdigest()}, separators=(",", ":")))
PY
}
if [[ -n "$ACCEPTANCE_JSON" ]]; then
  ACC="$(acceptance_from_release_truth "$ACCEPTANCE_JSON")" || fail "--acceptance-json is not a release-truth --json report"
  ACC_SOURCE="release-truth"; ACC_ENV="$(printf '%s' "$ACC" | jget "['environment']")"
  ACC_AT="$(printf '%s' "$ACC" | jget "['observedAt']")"
  ACC_REF="release-truth $(printf '%s' "$ACC" | jget "['workload']") $(printf '%s' "$ACC" | jget "['sha256']")"
  # The artifact of the ValueEvent is the image release-truth MEASURED: never claim acceptance of an image nobody measured.
  MEASURED_DIGEST="$(printf '%s' "$ACC" | jget "['measuredDigest']")"
  if [[ -n "$MEASURED_DIGEST" ]]; then
    [[ -z "$IMAGE_DIGEST" || "$IMAGE_DIGEST" == "$MEASURED_DIGEST" ]] \
      || fail "--image-digest ${IMAGE_DIGEST} is not the image release-truth measured (${MEASURED_DIGEST}): the ValueEvent would claim an acceptance nobody measured"
    IMAGE_DIGEST="$MEASURED_DIGEST"
  fi
else
  ACC='{"stages":{"MERGED":true,"BUILT":true,"DEPLOYED":true,"RUNNING":true,"ACCEPTED":true},"detail":{}}'
  ACC_SOURCE="manual"; ACC_ENV="cryptobot-demo"; ACC_AT=""; ACC_REF="pov-e2e.sh --assume-accepted"
fi

# ---- compute receipt: measured (--compute-json) or estimated --------------------------------------
if [[ -n "$COMPUTE_JSON" ]]; then
  COMPUTE="$(python3 - "$COMPUTE_JSON" <<'PY'
import json, sys
c = json.load(open(sys.argv[1]))
print(json.dumps({"providerId": "compute-node-8", "node": c.get("node") or "compute-node-8", "model": c["model"],
                  "inputTokens": int(c["inputTokens"]), "outputTokens": int(c["outputTokens"]),
                  "gpuSeconds": round(float(c["gpuSeconds"]), 3), "estimatedCostMicroUsd": int(c["estimatedCostMicroUsd"])},
                 separators=(",", ":")))
PY
)" || fail "--compute-json needs model, inputTokens, outputTokens, gpuSeconds, estimatedCostMicroUsd"
  COMPUTE_KIND="measured (--compute-json)"
else
  # Not measured: the numbers are an estimate and the anchored record says so in the model field.
  COMPUTE='{"providerId":"compute-node-8","node":"compute-node-8","model":"claude-opus (estimated)","inputTokens":182000,"outputTokens":24000,"gpuSeconds":12.5,"estimatedCostMicroUsd":4730000}'
  COMPUTE_KIND="estimated (no --compute-json)"
fi

ui() { printf '\033[2m  UI  %s%s\033[0m\n' "$UI_BASE" "$1"; UI_ROUTES+=("$1"); }
banner() { printf '\n\033[1;36m━━━ STEP %s/9 — %s\033[0m\n' "$1" "$2"; CURRENT_STEP="$1 — $2"; }
UI_ROUTES=(); CURRENT_STEP=""

if (( DRY_RUN )); then
  step "dry run — nothing is sent"
  echo "service  ${BASE}   ui ${UI_BASE}"
  echo "1 task      \"${TASK}\" (${TASK_ID}) — specified by sebas"
  echo "2 artifact  commit ${COMMIT:-<HEAD of origin/main via gh>} · pr ${PR_URL:-<its PR via gh>} · image ${IMAGE_DIGEST:-<GHCR sha-<commit> via gh>}"
  echo "            contributions sebas SPECIFIER+ARCHITECT · dev-agent-17 IMPLEMENTER · review-agent-3 REVIEWER · compute-node-8 COMPUTE_PROVIDER"
  echo "            assets strategy-knowledge@3 · production-acceptance-model@1 · compute ${COMPUTE_KIND}: ${COMPUTE}"
  echo "3 acceptance source=${ACC_SOURCE} environment=${ACC_ENV} stages=$(printf '%s' "$ACC" | jget "['stages']")${MEASURED_DIGEST:+ measured image ${MEASURED_DIGEST}}"
  (( ASSUME_ACCEPTED )) && printf '\033[1;31m            acceptance asserted manually\033[0m\n'
  echo "4 POST ${BASE}/api/cryptobot/value-events?anchor=true · GET /value-events/{id}/proof"
  echo "5 POST ${BASE}/api/cryptobot/value-events/{id}/distribute?anchor=true"
  if (( SKIP_OP )); then echo "6 CryptoBot operation skipped (--skip-op): nothing spent"
  else echo "6 ${HERE}/e2e-devnet.sh --base-url ${BASE} --env-file ${ENV_FILE} --sell-sol ${SELL_SOL} (one real devnet operation)"; fi
  echo "7 POST ${BASE}/api/cryptobot/revenue-events?anchor=true  (${REVENUE_LAMPORTS} lamports, simulated=true, source $( (( SKIP_OP )) && echo "SIMULATED pov-e2e:${TASK_ID}:<event>" || echo 'PROPOSAL <step 6>'))"
  echo "8 GET /value-events · /value-events/{id}/distribution · /revenue-events · /treasury/cryptobot-001"
  echo "9 GET /identities/{id} × ${POV_WALLET_IDS[*]} · /units/ledger?groupBy=identity"
  echo "report ${OUT_DIR}/pov-e2e-<ts>.md"
  exit 0
fi

# ---- run: log, report, secrets check on exit --------------------------------------------------------
TS="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$OUT_DIR"
REPORT="${OUT_DIR}/pov-e2e-${TS}.md"; LOG="${OUT_DIR}/pov-e2e-${TS}.log"; RUN_DIR="${OUT_DIR}/pov-e2e-${TS}"
mkdir -p "$RUN_DIR"
exec 3>&1 4>&2
exec > >(tee -a "$LOG") 2>&1
TEE_PID=$!
REP=()   # report rows: "| step | key | value |"
rep() { REP+=("| $1 | $2 | $3 |"); }
LAST_ERROR=""
fail() { LAST_ERROR="$*"; printf '\033[1;31m[demo]\033[0m %s\n' "$*" >&2; exit 1; }
write_report() {
  local rc="$1" r
  {
    echo "# Proof of Value — end-to-end run ${TS}"
    echo
    echo "- result: $([[ "$rc" -eq 0 ]] && echo PASSED || echo "FAILED at step ${CURRENT_STEP:-?} — ${LAST_ERROR:-exit ${rc}}")"
    echo "- service: \`${BASE}\` · UI: \`${UI_BASE}\` · task: \"${TASK}\" (\`${TASK_ID}\`)"
    echo "- acceptance source: \`${ACC_SOURCE}\`$([[ "$ACC_SOURCE" == manual ]] && echo ' — **asserted manually**, not measured')"
    echo "- CryptoBot operation: $( (( SKIP_OP )) && echo 'skipped (--skip-op); revenue source SIMULATED' || echo 'real (devnet)')"
    echo "- devnet SOL stands in for stablecoin settlement; the revenue amount is a simulated economic result."
    echo
    echo "| step | what | value |"
    echo "|---|---|---|"
    for r in "${REP[@]}"; do echo "$r"; done
    echo
    echo "UI screens shown: $(printf '`%s` ' "${UI_ROUTES[@]}")"
    echo
    echo "Keys live in \`${DEMO_HOME}\` (0600); JWT secret and tokens in the env file (gitignored). The report and the log were"
    echo "searched for every secret value of the env file after the run (\`secrets_scan\`, lib.sh)."
  } > "$REPORT"
}
on_exit() {
  local rc=$? sc=0
  write_report "$rc"
  exec 1>&3 2>&4
  wait "$TEE_PID" 2>/dev/null || true
  sed -i 's/\x1b\[[0-9;]*m//g' "$LOG"
  local scan; scan="$(secrets_scan "$ENV_FILE" "$REPORT" "$LOG")" || sc=$?
  printf '[demo] secrets check: %s\n' "$(printf '%s' "$scan" | tr '\n' ' ')" | tee -a "$LOG" >&2
  (( sc == 0 )) || exit 3
  if (( rc == 0 )); then printf '[demo] POV E2E PASSED — report: %s\n' "$REPORT" >&2
  else printf '[demo] POV E2E FAILED — %s — report: %s · log: %s\n' "${LAST_ERROR:-exit ${rc}}" "$REPORT" "$LOG" >&2; fi
  exit "$rc"
}
trap on_exit EXIT

if [[ -n "${CRYPTOBOT_DEMO_TOKEN:-}" ]]; then TOKEN="$CRYPTOBOT_DEMO_TOKEN"; else TOKEN="$(demo_token "$ENV_FILE")"; fi
RPC_URL="$(pov_rpc_url "$ENV_FILE")"
DEMO_WALLET="$(env_value DEMO_WALLET_ADDRESS)"
WALLET_START=""; [[ -n "$DEMO_WALLET" ]] && WALLET_START="$(balance_lamports "$RPC_URL" "$DEMO_WALLET" 2>/dev/null || true)"
curl -fsS -m 10 "${CURL_OPTS[@]}" "${BASE}/api/cryptobot/health" 2>/dev/null | grep -q '"UP"' || fail "service not UP at ${BASE}/api/cryptobot/health"

# ==== STEP 1 ==========================================================================================
banner 1 "Human defines task"
pov_seed_identities
pov_seed_assets
echo "task        \"${TASK}\""
echo "task id     ${TASK_ID}"
echo "specified   sebas (HUMAN) — SPECIFIER + ARCHITECT"
echo "assigned    dev-agent-17 (AGENT, owner sebas)"
rep 1 task "\"${TASK}\" (\`${TASK_ID}\`), specified by sebas"
ui "/value/identities/sebas"

# ==== STEP 2 ==========================================================================================
banner 2 "DevAgent implements"
REPO_SLUG="$(git -C "$PROJECT" remote get-url origin 2>/dev/null | sed -E 's#^(https://[^/]+/|git@[^:]+:)##; s#\.git$##' || true)"
[[ "$REPO_SLUG" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] || REPO_SLUG="sebdev89/life-engine-cryptobot-service"
if [[ -z "$COMMIT" || -z "$PR_URL" || -z "$IMAGE_DIGEST" ]]; then
  command -v gh >/dev/null 2>&1 || fail "gh not installed: pass --commit, --pr and --image-digest"
  if [[ -z "$COMMIT" && -n "$IMAGE_DIGEST" ]]; then
    # The commit of a known image (the measured one): its GHCR tag sha-<commit>.
    tag="$(gh api "/users/${REPO_SLUG%%/*}/packages/container/${REPO_SLUG##*/}/versions?per_page=100" \
      -q ".[] | select(.name == \"${IMAGE_DIGEST}\") | .metadata.container.tags[]" 2>/dev/null | grep -m1 '^sha-' || true)"
    [[ -n "$tag" ]] && COMMIT="$(gh api "repos/${REPO_SLUG}/commits/${tag#sha-}" -q .sha 2>/dev/null || true)"
    [[ "$COMMIT" =~ ^[0-9a-f]{7,64}$ ]] || fail "could not find the commit of ${IMAGE_DIGEST} in GHCR: pass --commit"
  fi
  [[ -n "$COMMIT" ]] || COMMIT="$(gh api "repos/${REPO_SLUG}/commits/main" -q .sha 2>/dev/null || true)"
  [[ "$COMMIT" =~ ^[0-9a-f]{7,64}$ ]] || fail "could not resolve HEAD of origin/main with gh: pass --commit"
  [[ -n "$PR_URL" ]] || PR_URL="$(gh api "repos/${REPO_SLUG}/commits/${COMMIT}/pulls" -q '.[0].html_url' 2>/dev/null || true)"
  [[ "$PR_URL" =~ ^https?:// ]] || fail "no PR found for ${COMMIT:0:12}: pass --pr"
  if [[ -z "$IMAGE_DIGEST" ]]; then
    IMAGE_DIGEST="$(gh api "/users/${REPO_SLUG%%/*}/packages/container/${REPO_SLUG##*/}/versions?per_page=100" \
      -q ".[] | select(.metadata.container.tags | index(\"sha-${COMMIT:0:7}\")) | .name" 2>/dev/null | head -1 || true)"
    [[ "$IMAGE_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "no GHCR image tagged sha-${COMMIT:0:7} (still building?): pass --commit/--image-digest of a published image"
  fi
fi
EVENT="$(T="$TASK" I="$TASK_ID" C="$COMMIT" P="$PR_URL" D="$IMAGE_DIGEST" ACC="$ACC" S="$ACC_SOURCE" E="$ACC_ENV" R="$ACC_REF" AT="$ACC_AT" \
  CT="$(git -C "$PROJECT" log -1 --format=%ct "$COMMIT" 2>/dev/null || true)" CMP="$COMPUTE" python3 - <<'PY'
import json, os, time
e = os.environ
at = e["AT"]
if not at:  # manual acceptance: the commit's date when this checkout knows it (same commit = same event), else now
    ct = e.get("CT", "")
    at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(int(ct) if ct.isdigit() else time.time()))
print(json.dumps({
  "projectId": "cryptobot", "taskId": e["I"], "title": e["T"],
  "artifact": {"commitSha": e["C"], "prUrl": e["P"], "imageDigest": e["D"]},
  "acceptance": {"source": e["S"], "environment": e["E"], "stages": json.loads(e["ACC"])["stages"], "evidenceRef": e["R"], "acceptedAt": at},
  "contributions": [{"identityId": "sebas", "role": "SPECIFIER"}, {"identityId": "sebas", "role": "ARCHITECT"},
                    {"identityId": "dev-agent-17", "role": "IMPLEMENTER"}, {"identityId": "review-agent-3", "role": "REVIEWER"},
                    {"identityId": "compute-node-8", "role": "COMPUTE_PROVIDER"}],
  "knowledgeAssets": ["strategy-knowledge@3", "production-acceptance-model@1"],
  "computeReceipts": [json.loads(e["CMP"])],
  "distributionPolicy": "pov/equal-split/v1"}, separators=(",", ":")))
PY
)"
echo "commit      ${COMMIT}"
echo "pr          ${PR_URL}"
echo "image       ${IMAGE_DIGEST}"
echo "roles       sebas SPECIFIER + ARCHITECT · dev-agent-17 IMPLEMENTER · review-agent-3 REVIEWER · compute-node-8 COMPUTE_PROVIDER"
echo "knowledge   strategy-knowledge@3 (STRATEGY) · production-acceptance-model@1 (RULESET) — creator sebas"
echo "compute     ${COMPUTE_KIND}: $(printf '%s' "$COMPUTE" | python3 -c 'import json,sys; c=json.load(sys.stdin); print("%s in=%s out=%s gpu=%ss cost=$%.2f" % (c["model"], c["inputTokens"], c["outputTokens"], c["gpuSeconds"], c["estimatedCostMicroUsd"]/1e6))')"
rep 2 artifact "commit \`${COMMIT}\` · [PR](${PR_URL}) · image \`${IMAGE_DIGEST}\`"
rep 2 compute "${COMPUTE_KIND}"

# ==== STEP 3 ==========================================================================================
banner 3 "Acceptance MERGED → BUILT → DEPLOYED → RUNNING → ACCEPTED"
printf '%s' "$ACC" | python3 -c '
import json, sys
a = json.load(sys.stdin)
for s in ("MERGED", "BUILT", "DEPLOYED", "RUNNING", "ACCEPTED"):
    ok = a["stages"][s]
    print("  %s %-9s %s" % ("\033[1;32m✓\033[0m" if ok else "\033[1;31m✗\033[0m", s, a.get("detail", {}).get(s, "")))'
echo "source      ${ACC_SOURCE} · environment ${ACC_ENV} · evidence ${ACC_REF}"
if [[ "$ACC_SOURCE" == release-truth ]]; then
  echo "observed    $(printf '%s' "$ACC" | jget "['observedAt']") · $(printf '%s' "$ACC" | jget "['commitNote']")"
else
  printf '\033[1;31m  acceptance asserted manually\033[0m (no release-truth report: --assume-accepted)\n'
fi
rep 3 acceptance "\`${ACC_SOURCE}\` / \`${ACC_ENV}\` — stages $(printf '%s' "$ACC" | jget "['stages']") — ${ACC_REF}"

# ==== STEP 4 ==========================================================================================
banner 4 "ValueEvent on Solana"
RESP="$(api POST '/api/cryptobot/value-events?anchor=true' "$EVENT")"; split_status
[[ "$STATUS" == 201 || "$STATUS" == 200 ]] || fail "POST /value-events → ${STATUS}: ${BODY}"
EVENT_JSON="$BODY"
VE_ID="$(printf '%s' "$EVENT_JSON" | jget "['id']")"; VE_STATE="$(printf '%s' "$EVENT_JSON" | jget "['status']")"
RESP="$(api GET "/api/cryptobot/value-events/${VE_ID}/proof")"; split_status
[[ "$STATUS" == 200 ]] || fail "GET /proof → ${STATUS}: ${BODY}"
PROOF="$BODY"
VE_TX="$(printf '%s' "$PROOF" | jget "['txSignature']")"; VE_ROOT="$(printf '%s' "$PROOF" | jget "['root']")"
VE_EXPLORER="$(printf '%s' "$PROOF" | jget "['explorerUrl']")"; VERIFIED="$(printf '%s' "$PROOF" | jget "['verified']")"
echo "value event ${VE_ID} (${VE_STATE})"
echo "receipt     $(printf '%s' "$EVENT_JSON" | jget "['receiptHash']")"
echo "root        ${VE_ROOT}"
echo "tx          ${VE_TX}"
echo "explorer    ${VE_EXPLORER}"
echo "units       $(printf '%s' "$EVENT_JSON" | python3 -c 'import json,sys; print(" · ".join("%s/%s=%s" % (c["identityId"], c["role"], c["units"]) for c in json.load(sys.stdin)["contributions"]))')"
echo "proof       verified=${VERIFIED}"
rep 4 "value event" "\`${VE_ID}\` ${VE_STATE} · receipt \`$(printf '%s' "$EVENT_JSON" | jget "['receiptHash']")\`"
rep 4 anchor "root \`${VE_ROOT}\` · tx [\`${VE_TX}\`](${VE_EXPLORER}) · proof verified=${VERIFIED}"
ui "/value/${VE_ID}"
[[ "$VE_STATE" == ANCHORED ]] || fail "the value event is ${VE_STATE}, not ANCHORED (signer up and funded?)"
[[ "$VERIFIED" == True ]] || fail "the proof is not verified"

# ==== STEP 5 ==========================================================================================
banner 5 "Immediate payout (devnet SOL stands in for stablecoin settlement)"
declare -A BEFORE=()
for id in "${POV_WALLET_IDS[@]}"; do BEFORE[$id]="$(balance_lamports "$RPC_URL" "$(pubkey_of "${DEMO_HOME}/pov-${id}.json")" 2>/dev/null || echo '?')"; done
RESP="$(api POST "/api/cryptobot/value-events/${VE_ID}/distribute?anchor=true" '')"; split_status
[[ "$STATUS" == 201 || "$STATUS" == 200 ]] || fail "POST /distribute → ${STATUS}: ${BODY}"
DIST="$BODY"; DSTATE="$(printf '%s' "$DIST" | jget "['status']")"
echo "distribution $(printf '%s' "$DIST" | jget "['id']") ${DSTATE} policy=$(printf '%s' "$DIST" | jget "['policy']") pool=$(printf '%s' "$DIST" | jget "['poolLamports']") lamports"
echo "payouts"; printf '%s' "$DIST" | pov_print_payouts
echo "balances (lamports)"
for id in "${POV_WALLET_IDS[@]}"; do
  printf '  %-15s before=%-12s after=%s\n' "$id" "${BEFORE[$id]}" "$(balance_lamports "$RPC_URL" "$(pubkey_of "${DEMO_HOME}/pov-${id}.json")" 2>/dev/null || echo '?')"
done
while IFS= read -r row; do rep 5 payout "$row"; done < <(printf '%s' "$DIST" | python3 -c '
import json, sys
for p in json.load(sys.stdin)["payouts"]:
    tx = ("[`%s`](%s)" % (p["txSignature"], p.get("explorerUrl") or "")) if p.get("txSignature") else "-"
    print("%s %s %s lamports %s" % (p["identityId"], p["status"], p["lamports"], tx))')
rep 5 distribution "\`$(printf '%s' "$DIST" | jget "['id']")\` ${DSTATE} · receipt \`$(printf '%s' "$DIST" | jget "['receiptHash']")\`"
ui "/value/${VE_ID}"
case "$DSTATE" in COMPLETE) ;; PARTIAL|IN_PROGRESS) warn "distribution ${DSTATE}: see the payouts above" ;; *) fail "distribution ${DSTATE}: nothing was paid" ;; esac

# ==== STEP 6 ==========================================================================================
banner 6 "CryptoBot uses the feature (one real operation on devnet)"
PROPOSAL_ID=""
if (( SKIP_OP )); then
  echo "SKIPPED (--skip-op): no devnet operation — the revenue source of step 7 is SIMULATED"
  rep 6 operation "skipped (--skip-op)"
else
  # e2e-devnet.sh is act 1 of run.sh: intent → plan → simulation → policy → approve → timelock → execute → EXECUTED → receipt.
  # --base-url: no docker; the same token (same operator) so the proposal is visible to the revenue event.
  POV_E2E_TOKEN="$TOKEN" "${HERE}/e2e-devnet.sh" --base-url "$BASE" --env-file "$ENV_FILE" --token-env POV_E2E_TOKEN \
    --sell-sol "$SELL_SOL" --evidence "${RUN_DIR}/op-evidence.md" --summary "${RUN_DIR}/op.env" \
    > "${RUN_DIR}/op.log" 2>&1 || { tail -15 "${RUN_DIR}/op.log"; fail "the CryptoBot operation failed (e2e-devnet.sh) — ${RUN_DIR}/op.log"; }
  declare -A OP=(); read_summary "${RUN_DIR}/op.env" OP
  PROPOSAL_ID="${OP[PROPOSAL_ID]:-}"
  echo "proposal    ${PROPOSAL_ID} — ${OP[PLAN]:-} (${OP[DECISION]:-}/${OP[TIER]:-})"
  echo "executed    ${OP[CONFIRMATION]:-?} · slot ${OP[SLOT]:-?}"
  echo "tx          ${OP[SIGNATURE]:-?}"
  echo "explorer    ${OP[EXPLORER]:-?}"
  echo "receipt     ${OP[RECEIPT_EXECUTION]:-?} (EXECUTION, valid=${OP[RECEIPT_VALID]:-?})"
  rep 6 operation "proposal \`${PROPOSAL_ID}\` EXECUTED · tx [\`${OP[SIGNATURE]:-?}\`](${OP[EXPLORER]:-}) · receipt \`${OP[RECEIPT_EXECUTION]:-?}\`"
  ui "/live/${PROPOSAL_ID}"
fi

# ==== STEP 7 ==========================================================================================
banner 7 "RevenueEvent (a SIMULATED economic result — not real profit)"
revenue_body() { # <kind> <ref>
  K="$1" R="$2" E="$VE_ID" A="$REVENUE_LAMPORTS" python3 -c '
import json, os
print(json.dumps({"projectId": "cryptobot", "source": {"kind": os.environ["K"], "ref": os.environ["R"]}, "amountLamports": int(os.environ["A"]),
                  "linkedValueEventIds": [os.environ["E"]], "simulated": True}, separators=(",", ":")))'
}
if [[ -n "$PROPOSAL_ID" ]]; then
  RESP="$(api POST '/api/cryptobot/revenue-events?anchor=true' "$(revenue_body PROPOSAL "$PROPOSAL_ID")")"; split_status
  if [[ "$STATUS" == 409 || "$STATUS" == 422 ]]; then warn "PROPOSAL source refused (${STATUS}: ${BODY}); falling back to SIMULATED"; PROPOSAL_ID=""; fi
fi
if [[ -z "$PROPOSAL_ID" ]]; then
  RESP="$(api POST '/api/cryptobot/revenue-events?anchor=true' "$(revenue_body SIMULATED "pov-e2e:${TASK_ID}:${VE_ID}")")"; split_status
fi
[[ "$STATUS" == 201 || "$STATUS" == 200 ]] || fail "POST /revenue-events → ${STATUS}: ${BODY}"
REV="$BODY"; REV_ID="$(printf '%s' "$REV" | jget "['id']")"; RSTATE="$(printf '%s' "$REV" | jget "['status']")"
printf '%s' "$REV" | pov_print_revenue "$STATUS"
echo "payouts"; printf '%s' "$REV" | pov_print_payouts
rep 7 revenue "\`${REV_ID}\` ${RSTATE} · source $(printf '%s' "$REV" | jget "['source']['kind']"):\`$(printf '%s' "$REV" | jget "['source']['ref']")\` · ${REVENUE_LAMPORTS} lamports simulated · pool $(printf '%s' "$REV" | jget "['contributorPoolLamports']") · fee $(printf '%s' "$REV" | jget "['protocolFeeLamports']") · retained $(printf '%s' "$REV" | jget "['retainedLamports']")"
rep 7 receipt "\`$(printf '%s' "$REV" | jget "['receiptHash']")\` anchored in \`$(printf '%s' "$REV" | jget "['anchor']['txSignature']")\`"
while IFS= read -r row; do rep 7 payout "$row"; done < <(printf '%s' "$REV" | python3 -c '
import json, sys
for p in json.load(sys.stdin)["payouts"]:
    tx = ("[`%s`](%s)" % (p["txSignature"], p.get("explorerUrl") or "")) if p.get("txSignature") else "-"
    print("%s %s %s lamports %s" % (p["identityId"], p["status"], p["lamports"], tx))')
ui "/value/revenue/${REV_ID}"
case "$RSTATE" in COMPLETE) ;; PARTIAL|IN_PROGRESS) warn "revenue event ${RSTATE}: see the payouts above" ;; *) fail "revenue event ${RSTATE}: nothing was paid" ;; esac

# ==== STEP 8 ==========================================================================================
banner 8 "Historical contributor distribution"
RESP="$(api GET '/api/cryptobot/value-events?limit=100')"; split_status
[[ "$STATUS" == 200 ]] || fail "GET /value-events → ${STATUS}"
: > "${RUN_DIR}/distributions.jsonl"
while IFS= read -r id; do
  RESP="$(api GET "/api/cryptobot/value-events/${id}/distribution")"; split_status
  [[ "$STATUS" == 200 ]] && printf '%s\n' "$BODY" >> "${RUN_DIR}/distributions.jsonl"
done < <(printf '%s' "$BODY" | python3 -c 'import json,sys; [print(e["id"]) for e in json.load(sys.stdin)]')
RESP="$(api GET '/api/cryptobot/revenue-events?limit=100')"; split_status
[[ "$STATUS" == 200 ]] || fail "GET /revenue-events → ${STATUS}"
printf '%s' "$BODY" > "${RUN_DIR}/revenue-events.json"
HIST="$(python3 - "${RUN_DIR}/distributions.jsonl" "${RUN_DIR}/revenue-events.json" <<'PY'
import json, sys
tot = {}
def add(p, k):
    if p["status"] != "CONFIRMED": return
    t = tot.setdefault(p["identityId"], {"reward": 0, "revenue": 0, "n": 0}); t[k] += p["lamports"]; t["n"] += 1
dists = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
revs = json.load(open(sys.argv[2]))
for d in dists:
    for p in d["payouts"]: add(p, "reward")
for r in revs:
    for p in r["payouts"]: add(p, "revenue")
print("  %-15s %14s %14s %14s %8s" % ("contributor", "reward (V5)", "revenue (V7)", "total", "payouts"))
for i, t in sorted(tot.items(), key=lambda kv: -(kv[1]["reward"] + kv[1]["revenue"])):
    print("  %-15s %14d %14d %14d %8d" % (i, t["reward"], t["revenue"], t["reward"] + t["revenue"], t["n"]))
print("  over %d distributions and %d revenue events (CONFIRMED lamports only)" % (len(dists), len(revs)))
PY
)"
printf '%s\n' "$HIST"
rep 8 historical "$(printf '%s' "$HIST" | tail -1 | sed 's/^ *//')"
RESP="$(api GET /api/cryptobot/treasury/cryptobot-001)"; split_status
[[ "$STATUS" == 200 ]] || fail "GET /treasury/cryptobot-001 → ${STATUS}: ${BODY}"
TREASURY="$BODY"
echo "treasury cryptobot-001 (accounting view; payouts are signed from the demo wallet)"
printf '%s' "$TREASURY" | pov_print_treasury
rep 8 treasury "income $(printf '%s' "$TREASURY" | jget "['incomeLamports']") · contributors paid $(printf '%s' "$TREASURY" | jget "['contributorPayoutsLamports']") · fee $(printf '%s' "$TREASURY" | jget "['protocolFeeLamports']") · retained $(printf '%s' "$TREASURY" | jget "['retainedLamports']") lamports"
ui "/value/treasury"

# ==== STEP 9 ==========================================================================================
banner 9 "Reputation + Contribution Units"
for id in "${POV_WALLET_IDS[@]}"; do
  RESP="$(api GET "/api/cryptobot/identities/${id}")"; split_status
  [[ "$STATUS" == 200 ]] || fail "GET /identities/${id} → ${STATUS}"
  line="$(printf '%s' "$BODY" | python3 -c '
import json, sys
i = json.load(sys.stdin); r = i.get("reputation") or {}
print("%-15s %-5s outcomes=%-3s units=%-5s history=%s wallet=%s" % (i["id"], i["kind"], r.get("acceptedOutcomes", 0), r.get("totalUnits", 0), len(i.get("history") or []), i.get("wallet") or "-"))')"
  echo "  ${line}"; rep 9 identity "${line}"
done
ui "/value/identities/dev-agent-17"
RESP="$(api GET '/api/cryptobot/units/ledger?groupBy=identity')"; split_status
[[ "$STATUS" == 200 ]] || fail "GET /units/ledger → ${STATUS}"
LEDGER="$BODY"
echo "ledger by identity (Contribution Units: an attribution primitive, not equity, not a promise of return)"
printf '%s' "$LEDGER" | python3 -c '
import json, sys
l = json.load(sys.stdin)
for r in l["rows"]: print("  %-15s units=%-5s outcomes=%s" % (r["key"], r["totalUnits"], r["acceptedOutcomes"]))
print("  %-15s units=%s" % ("TOTAL", l["totalUnits"]))'
rep 9 ledger "total units $(printf '%s' "$LEDGER" | jget "['totalUnits']")"
ui "/value/ledger"

# ==== final screen ===================================================================================
WALLET_END=""; [[ -n "$DEMO_WALLET" ]] && WALLET_END="$(balance_lamports "$RPC_URL" "$DEMO_WALLET" 2>/dev/null || true)"
COST=""; [[ "$WALLET_START" =~ ^[0-9]+$ && "$WALLET_END" =~ ^[0-9]+$ ]] && COST="$(( WALLET_START - WALLET_END ))"
FINAL="$(EVENT_JSON="$EVENT_JSON" DIST="$DIST" REV="$REV" COST="$COST" SKIP_OP="$SKIP_OP" python3 - <<'PY'
import json, os
ev, d, r = json.loads(os.environ["EVENT_JSON"]), json.loads(os.environ["DIST"]), json.loads(os.environ["REV"])
paid = {}
for p in d["payouts"] + r["payouts"]:
    if p["status"] == "CONFIRMED": paid[p["identityId"]] = paid.get(p["identityId"], 0) + p["lamports"]
units = {}
for c in ev["contributions"]: units[c["identityId"]] = units.get(c["identityId"], 0) + c["units"]
kp = sum(c["units"] for c in ev["contributions"] if c["role"] == "KNOWLEDGE_PROVIDER")
sol = lambda l: "%.4f SOL" % (l / 1e9)
print("VALUE GENERATED %g SOL (simulated: %s)" % (r["amountLamports"] / 1e9, "yes" if r["simulated"] else "no"))
print("ATTRIBUTION: sebas · dev-agent-17 · strategy-knowledge@3 · compute-node-8 · review-agent-3 · treasury · protocol")
for who in ("sebas", "dev-agent-17", "compute-node-8", "review-agent-3"):
    print("  %-22s %3s units   %s received" % (who, units.get(who, 0), sol(paid.get(who, 0))))
print("  %-22s %3s units   (knowledge; credited to its creator sebas)" % ("strategy-knowledge@3", kp))
print("  %-22s       %s retained" % ("treasury (cryptobot-001)", sol(r["retainedLamports"])))
print("  %-22s       %s fee (recorded only)" % ("protocol", sol(r["protocolFeeLamports"])))
c = os.environ.get("COST", "")
if c:
    print("demo wallet spent %.6f SOL (payouts + fees%s)" % (int(c) / 1e9, "" if os.environ.get("SKIP_OP") == "1" else " + the operation's transfer to the vault"))
PY
)"
printf '\n\033[1;35m%s\033[0m\n' "$FINAL"
rep final "value generated" "$(printf '%s' "$FINAL" | head -1)"
[[ -n "$COST" ]] && rep final cost "$(awk -v c="$COST" 'BEGIN{printf "%.6f SOL from the demo wallet", c/1e9}')$( (( SKIP_OP )) || echo " (includes the operation's transfer to the vault)")"
ui "/value"
CURRENT_STEP=""
log "PASS — task → accepted outcome → ValueEvent anchored → contributors paid → $( (( SKIP_OP )) && echo 'simulated' || echo 'CryptoBot operation →') revenue shared → reputation and units"
