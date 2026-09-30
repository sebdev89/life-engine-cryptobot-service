#!/usr/bin/env bash
# shellcheck disable=SC2034  # TOKEN/BASE/CURL_OPTS are read by the helpers sourced from lib.sh
# KAN-818 / KAN-819 — Proof of Value V1–V4 + V6 against a running stack: identities with wallet →
# knowledge assets → an ACCEPTED contribution that used them, with its compute receipt → ValueEvent →
# VALUE_EVENT receipt → Merkle batch → memo on Solana devnet → proof → reputation and units ledger.
#
#   scripts/demo/pov-v1.sh [--env-file <f>] [--base-url <url>] [--commit <sha>] [--accepted-at <ISO>] [--dry-run]
#
# Steps: 1 identities  sebas (HUMAN), dev-agent-17 (AGENT, owner sebas), cryptobot-001 (AGENT, owner+operator
#                       sebas), review-agent-3 (AGENT), compute-node-8 (AGENT, owner sebas). Each AGENT wallet is
#                       a devnet keypair ~/.cryptobot-demo/pov-<id>.json, generated once (solana-keygen new
#                       --no-bip39-passphrase, or the same layout from python) — ONLY the public key is sent.
#        2 knowledge    production-acceptance-model@1 (RULESET) and strategy-knowledge@3 (STRATEGY), creator sebas
#        3 value event  "Improve CryptoBot opportunity detection", taskId KAN-819, commitSha = HEAD (or --commit),
#                       both assets, one compute receipt of compute-node-8, POST /value-events?anchor=true.
#                       acceptedAt = the commit's date (or --accepted-at): the same commit is the same event.
#        4 proof        GET /value-events/{id}/proof
#        5 read models  GET /identities (reputation) and /units/ledger?groupBy=identity
# Idempotent: identities, assets and the event are returned as stored when they exist (200). Exit 0 only
# when the event is ANCHORED and the proof is verified; 1 otherwise.
#
# Auth: the same as run.sh/ui-url.sh — local target: an HS256 token (RUNTIME_OPERATOR + RUNTIME_ADMIN, 1 h)
# minted with JWT_SECRET of the env file (default ./.env.demo), subject = the demo operator of ui-url.sh, so
# the UI opened with ui-url.sh shows the same events. Another target: --base-url plus CRYPTOBOT_DEMO_TOKEN in
# the environment (a real Life Engine JWT with RUNTIME_ADMIN). The token is never printed.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "${HERE}/../.." && pwd)"
# shellcheck source=lib.sh
source "${HERE}/lib.sh"

ENV_FILE="${PROJECT}/.env.demo"; BASE=""; COMMIT=""; ACCEPTED_AT=""; DRY_RUN=0
while (( $# )); do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --base-url) BASE="${2%/}"; shift 2 ;;
    --commit) COMMIT="$2"; shift 2 ;;
    --accepted-at) ACCEPTED_AT="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '3,29p' "$0"; exit 0 ;;
    *) fail "unknown argument: $1 (see --help)" ;;
  esac
done
need curl; need python3
[[ -n "$COMMIT" ]] || COMMIT="$(git -C "$PROJECT" rev-parse HEAD 2>/dev/null || true)"
[[ "$COMMIT" =~ ^[0-9a-f]{7,64}$ ]] || fail "no commit sha (not a git checkout? pass --commit <sha>)"

CURL_OPTS=()
if [[ -z "$BASE" ]]; then
  [[ -f "$ENV_FILE" ]] || fail "no ${ENV_FILE}: run scripts/demo/run.sh --keep first, or pass --base-url with CRYPTOBOT_DEMO_TOKEN"
  port="$(sed -n 's/^CRYPTOBOT_DEMO_PORT=//p' "$ENV_FILE" | tail -1)"
  BASE="http://127.0.0.1:${CRYPTOBOT_DEMO_PORT:-${port:-8091}}"
fi

if [[ -z "$ACCEPTED_AT" ]]; then
  ct="$(git -C "$PROJECT" log -1 --format=%ct "$COMMIT" 2>/dev/null || true)"
  if [[ "$ct" =~ ^[0-9]+$ ]]; then ACCEPTED_AT="$(date -u -d "@${ct}" +%Y-%m-%dT%H:%M:%SZ)"; else ACCEPTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; fi
fi
[[ "$ACCEPTED_AT" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || fail "--accepted-at must be UTC ISO-8601 (YYYY-MM-DDTHH:MM:SSZ)"

AGENTS=(dev-agent-17 cryptobot-001 review-agent-3 compute-node-8)
# identity_body <id> <wallet>: the POST /identities body of a seed identity.
identity_body() {
  case "$1" in
    sebas)          printf '{"id":"sebas","kind":"HUMAN","displayName":"Sebastián"}' ;;
    dev-agent-17)   printf '{"id":"dev-agent-17","kind":"AGENT","displayName":"Dev Agent 17","wallet":"%s","ownerId":"sebas"}' "$2" ;;
    cryptobot-001)  printf '{"id":"cryptobot-001","kind":"AGENT","displayName":"CryptoBot 001","wallet":"%s","ownerId":"sebas","operatorId":"sebas"}' "$2" ;;
    review-agent-3) printf '{"id":"review-agent-3","kind":"AGENT","displayName":"Review Agent 3","wallet":"%s"}' "$2" ;;
    compute-node-8) printf '{"id":"compute-node-8","kind":"AGENT","displayName":"Compute Node 8","wallet":"%s","ownerId":"sebas"}' "$2" ;;
  esac
}
# The content of each asset is hashed here; only the hash is registered.
ASSETS_JSON="$(python3 - <<'PY'
import hashlib, json
def h(t): return "sha256:" + hashlib.sha256(t.encode()).hexdigest()
print(json.dumps([
  {"id": "production-acceptance-model@1", "version": 1, "kind": "RULESET", "title": "Production acceptance model",
   "creatorId": "sebas", "contentHash": h("A contribution is accepted only when MERGED -> BUILT -> DEPLOYED -> RUNNING -> ACCEPTED all hold, measured by release-truth."),
   "parentIds": []},
  {"id": "strategy-knowledge@3", "version": 3, "kind": "STRATEGY", "title": "CryptoBot strategy knowledge",
   "creatorId": "sebas", "contentHash": h("cryptobot strategy knowledge v3: opportunity detection, risk gates, rebalance policy"),
   "parentIds": []}], separators=(",", ":")))
PY
)"
EVENT="$(C="$COMMIT" T="$ACCEPTED_AT" python3 - <<'PY'
import json, os
print(json.dumps({
  "projectId": "cryptobot", "taskId": "KAN-819", "title": "Improve CryptoBot opportunity detection",
  "artifact": {"commitSha": os.environ["C"], "prUrl": "https://github.com/sebdev89/life-engine-cryptobot-service"},
  "acceptance": {"source": "pov-v1.sh", "environment": "cryptobot-demo",
                 "stages": {"MERGED": True, "BUILT": True, "DEPLOYED": True, "RUNNING": True, "ACCEPTED": True},
                 "evidenceRef": "scripts/demo/pov-v1.sh", "acceptedAt": os.environ["T"]},
  "contributions": [{"identityId": "sebas", "role": "SPECIFIER"},
                    {"identityId": "dev-agent-17", "role": "IMPLEMENTER"},
                    {"identityId": "review-agent-3", "role": "REVIEWER"},
                    {"identityId": "cryptobot-001", "role": "OPERATOR"}],
  "knowledgeAssets": ["production-acceptance-model@1", "strategy-knowledge@3"],
  "computeReceipts": [{"providerId": "compute-node-8", "node": "compute-node-8", "model": "claude-opus",
                       "inputTokens": 182000, "outputTokens": 24000, "gpuSeconds": 12.5, "estimatedCostMicroUsd": 4730000}],
  "distributionPolicy": "pov/equal-split/v1"}, separators=(",", ":")))
PY
)"

if (( DRY_RUN )); then
  step "dry run — nothing is sent"
  echo "service  ${BASE}"
  echo "1 POST ${BASE}/api/cryptobot/identities  × sebas (HUMAN) · dev-agent-17 · cryptobot-001 · review-agent-3 · compute-node-8 (AGENT)"
  for a in "${AGENTS[@]}"; do echo "  ${a}: wallet = public key of ${DEMO_HOME}/pov-${a}.json ($([[ -f "${DEMO_HOME}/pov-${a}.json" ]] && echo exists || echo 'generated on the real run'))"; done
  echo "2 POST ${BASE}/api/cryptobot/knowledge-assets  × production-acceptance-model@1 · strategy-knowledge@3"
  echo "  ${ASSETS_JSON}"
  echo "3 POST ${BASE}/api/cryptobot/value-events?anchor=true"
  echo "  ${EVENT}"
  echo "4 GET  ${BASE}/api/cryptobot/value-events/{id}/proof"
  echo "5 GET  ${BASE}/api/cryptobot/identities · ${BASE}/api/cryptobot/units/ledger?groupBy=identity"
  exit 0
fi

if [[ -n "${CRYPTOBOT_DEMO_TOKEN:-}" ]]; then
  TOKEN="$CRYPTOBOT_DEMO_TOKEN"
else
  [[ -f "$ENV_FILE" ]] || fail "no ${ENV_FILE} and no CRYPTOBOT_DEMO_TOKEN"
  secret="$(sed -n 's/^JWT_SECRET=//p' "$ENV_FILE" | tail -1)"
  [[ -n "$secret" ]] || fail "JWT_SECRET missing in ${ENV_FILE}"
  sub="$(python3 -c 'import uuid; print(uuid.uuid5(uuid.NAMESPACE_DNS, "demo@cryptobot.local"))')"
  TOKEN="$(jwt_hs256 "$secret" "$sub" "demo@cryptobot.local")"
  unset secret
fi

step "1 identities (agents: devnet keypair in ${DEMO_HOME}, only the public key is registered)"
mkdir -p "$DEMO_HOME"; chmod 700 "$DEMO_HOME"
for id in sebas "${AGENTS[@]}"; do
  wallet=""
  if [[ "$id" != sebas ]]; then
    kp="${DEMO_HOME}/pov-${id}.json"
    [[ -f "$kp" ]] || { keygen "$kp"; log "keypair generated: ${kp}"; }
    wallet="$(pubkey_of "$kp")"
  fi
  RESP="$(api POST /api/cryptobot/identities "$(identity_body "$id" "$wallet")")"; split_status
  [[ "$STATUS" == 201 || "$STATUS" == 200 ]] || fail "POST /identities ${id} → ${STATUS}: ${BODY}"
  stored="$(printf '%s' "$BODY" | jget "['wallet']")"
  [[ "$stored" == None ]] && stored=""
  if [[ -n "$wallet" && "$stored" != "$wallet" ]]; then
    warn "identity ${id} already has another wallet (${stored:-none}); the service never overwrites a wallet"
  fi
  log "identity ${id} ($(printf '%s' "$BODY" | jget "['kind']")) wallet=${stored:-none} → ${STATUS}"
done

step "2 knowledge assets"
while IFS= read -r body; do
  RESP="$(api POST /api/cryptobot/knowledge-assets "$body")"; split_status
  [[ "$STATUS" == 201 || "$STATUS" == 200 ]] || fail "POST /knowledge-assets → ${STATUS}: ${BODY}"
  log "asset $(printf '%s' "$BODY" | jget "['id']") ($(printf '%s' "$BODY" | jget "['kind']")) → ${STATUS}"
done < <(printf '%s' "$ASSETS_JSON" | python3 -c 'import json,sys; [print(json.dumps(a, separators=(",", ":"))) for a in json.load(sys.stdin)]')

step "3 value event (anchor=true: the sweep of POST /anchors?wait=true)"
RESP="$(api POST '/api/cryptobot/value-events?anchor=true' "$EVENT")"; split_status
[[ "$STATUS" == 201 || "$STATUS" == 200 ]] || fail "POST /value-events → ${STATUS}: ${BODY}"
EVENT_JSON="$BODY"
ID="$(printf '%s' "$EVENT_JSON" | jget "['id']")"
STATE="$(printf '%s' "$EVENT_JSON" | jget "['status']")"
log "value event ${ID} → ${STATUS} ${STATE} (batch $(printf '%s' "$EVENT_JSON" | jget "['anchorStatus']"))"

step "4 proof"
RESP="$(api GET "/api/cryptobot/value-events/${ID}/proof")"; split_status
[[ "$STATUS" == 200 ]] || fail "GET /proof → ${STATUS}: ${BODY}"
PROOF="$BODY"

echo
echo "receiptHash     $(printf '%s' "$EVENT_JSON" | jget "['receiptHash']")"
echo "valueEventHash  $(printf '%s' "$EVENT_JSON" | jget "['valueEventHash']")"
echo "artifactHash    $(printf '%s' "$EVENT_JSON" | jget "['artifactHash']")"
echo "acceptanceHash  $(printf '%s' "$EVENT_JSON" | jget "['acceptanceHash']")"
echo "status          ${STATE}"
echo "root            $(printf '%s' "$PROOF" | jget "['root']")"
echo "txSignature     $(printf '%s' "$PROOF" | jget "['txSignature']")"
echo "explorerUrl     $(printf '%s' "$PROOF" | jget "['explorerUrl']")"
echo "units           $(printf '%s' "$EVENT_JSON" | python3 -c 'import json,sys; print(" · ".join("%s/%s=%s" % (c["identityId"], c["role"], c["units"]) for c in json.load(sys.stdin)["contributions"]))')"
echo "knowledge       $(printf '%s' "$EVENT_JSON" | python3 -c 'import json,sys; print(" · ".join("%s (%s, creator %s)" % (k["id"], k["kind"], k["creatorId"]) for k in json.load(sys.stdin).get("knowledgeAssets") or []))')"
echo "compute         $(printf '%s' "$EVENT_JSON" | python3 -c 'import json,sys; print(" · ".join("%s %s in=%s out=%s gpu=%ss cost=$%.2f wallet=%s" % (c["providerId"], c["model"], c["inputTokens"], c["outputTokens"], c["gpuSeconds"], c["estimatedCostMicroUsd"] / 1e6, c["providerWallet"]) for c in json.load(sys.stdin).get("computeReceipts") or []))')"
echo "proof           $(printf '%s' "$PROOF" | python3 -c 'import json,sys; p=json.load(sys.stdin); print(json.dumps({k: p.get(k) for k in ("verified","valid","hashMatchesCanonical","signatureValid","valueEventHashValid")} | {"proofValid": (p.get("anchor") or {}).get("proofValid")}))')"

step "5 read models: reputation and Contribution Units ledger"
RESP="$(api GET /api/cryptobot/identities)"; split_status
[[ "$STATUS" == 200 ]] || fail "GET /identities → ${STATUS}: ${BODY}"
echo "identities"
printf '%s' "$BODY" | python3 -c '
import json, sys
for i in json.load(sys.stdin):
    r = i.get("reputation") or {}
    print("  %-15s %-5s outcomes=%-3s units=%-5s wallet=%s" % (i["id"], i["kind"], r.get("acceptedOutcomes", 0), r.get("totalUnits", 0), i.get("wallet") or "-"))'
RESP="$(api GET '/api/cryptobot/units/ledger?groupBy=identity')"; split_status
[[ "$STATUS" == 200 ]] || fail "GET /units/ledger → ${STATUS}: ${BODY}"
echo "ledger by identity (Contribution Units: an attribution primitive, not equity, not a promise of return)"
printf '%s' "$BODY" | python3 -c '
import json, sys
l = json.load(sys.stdin)
for r in l["rows"]:
    print("  %-15s units=%-5s outcomes=%s" % (r["key"], r["totalUnits"], r["acceptedOutcomes"]))
print("  %-15s units=%s" % ("TOTAL", l["totalUnits"]))'
echo "anchor tx       $(printf '%s' "$PROOF" | jget "['txSignature']")"

VERIFIED="$(printf '%s' "$PROOF" | jget "['verified']")"
[[ "$STATE" == "ANCHORED" ]] || fail "the value event is ${STATE}, not ANCHORED (is the signer up and funded? retry: POST /api/cryptobot/anchors?wait=true)"
[[ "$VERIFIED" == "True" ]] || fail "the proof is not verified"
log "PASS — ValueEvent (with knowledge and compute attribution) anchored on devnet and verified"
