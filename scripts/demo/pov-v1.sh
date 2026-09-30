#!/usr/bin/env bash
# shellcheck disable=SC2034  # TOKEN/BASE/CURL_OPTS are read by the helpers sourced from lib.sh
# KAN-818 — Proof of Value V1 against a running stack: an ACCEPTED contribution → ValueEvent →
# VALUE_EVENT receipt → Merkle batch → memo on Solana devnet → proof.
#
#   scripts/demo/pov-v1.sh [--env-file <f>] [--base-url <url>] [--commit <sha>] [--dry-run]
#
# Steps: 1 identities  sebas (HUMAN), dev-agent-17 (AGENT, owner sebas), cryptobot-001 (AGENT, owner sebas)
#        2 value event  "Improve CryptoBot opportunity detection", taskId KAN-818, commitSha = HEAD of this
#                       checkout (or --commit), the five acceptance stages true, POST /value-events?anchor=true
#        3 proof        GET /value-events/{id}/proof
# Prints receiptHash, root, txSignature, explorerUrl and the proof. Exit 0 only when the event is
# ANCHORED and the proof is verified; 1 otherwise.
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

ENV_FILE="${PROJECT}/.env.demo"; BASE=""; COMMIT=""; DRY_RUN=0
while (( $# )); do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --base-url) BASE="${2%/}"; shift 2 ;;
    --commit) COMMIT="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '3,18p' "$0"; exit 0 ;;
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

ACCEPTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
EVENT="$(C="$COMMIT" T="$ACCEPTED_AT" python3 - <<'PY'
import json, os
print(json.dumps({
  "projectId": "cryptobot", "taskId": "KAN-818", "title": "Improve CryptoBot opportunity detection",
  "artifact": {"commitSha": os.environ["C"], "prUrl": "https://github.com/sebdev89/life-engine-cryptobot-service"},
  "acceptance": {"source": "pov-v1.sh", "environment": "cryptobot-demo",
                 "stages": {"MERGED": True, "BUILT": True, "DEPLOYED": True, "RUNNING": True, "ACCEPTED": True},
                 "evidenceRef": "scripts/demo/pov-v1.sh", "acceptedAt": os.environ["T"]},
  "contributions": [{"identityId": "sebas", "role": "SPECIFIER"},
                    {"identityId": "dev-agent-17", "role": "IMPLEMENTER"},
                    {"identityId": "cryptobot-001", "role": "OPERATOR"}],
  "distributionPolicy": "pov/equal-split/v1"}, separators=(",", ":")))
PY
)"

if (( DRY_RUN )); then
  step "dry run — nothing is sent"
  echo "service  ${BASE}"
  echo "1 POST ${BASE}/api/cryptobot/identities  × sebas (HUMAN) · dev-agent-17 (AGENT, owner sebas) · cryptobot-001 (AGENT, owner sebas)"
  echo "2 POST ${BASE}/api/cryptobot/value-events?anchor=true"
  echo "  ${EVENT}"
  echo "3 GET  ${BASE}/api/cryptobot/value-events/{id}/proof"
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

step "1 identities"
for body in '{"id":"sebas","kind":"HUMAN","displayName":"Sebastián"}' \
            '{"id":"dev-agent-17","kind":"AGENT","displayName":"Dev Agent 17","ownerId":"sebas"}' \
            '{"id":"cryptobot-001","kind":"AGENT","displayName":"CryptoBot 001","ownerId":"sebas","operatorId":"sebas"}'; do
  RESP="$(api POST /api/cryptobot/identities "$body")"; split_status
  [[ "$STATUS" == 201 || "$STATUS" == 200 ]] || fail "POST /identities → ${STATUS}: ${BODY}"
  log "identity $(printf '%s' "$BODY" | jget "['id']") ($(printf '%s' "$BODY" | jget "['kind']")) → ${STATUS}"
done

step "2 value event (anchor=true: the sweep of POST /anchors?wait=true)"
RESP="$(api POST '/api/cryptobot/value-events?anchor=true' "$EVENT")"; split_status
[[ "$STATUS" == 201 || "$STATUS" == 200 ]] || fail "POST /value-events → ${STATUS}: ${BODY}"
EVENT_JSON="$BODY"
ID="$(printf '%s' "$EVENT_JSON" | jget "['id']")"
STATE="$(printf '%s' "$EVENT_JSON" | jget "['status']")"
log "value event ${ID} → ${STATUS} ${STATE} (batch $(printf '%s' "$EVENT_JSON" | jget "['anchorStatus']"))"

step "3 proof"
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
echo "units           $(printf '%s' "$EVENT_JSON" | python3 -c 'import json,sys; print(" · ".join("%s=%s" % (c["identityId"], c["units"]) for c in json.load(sys.stdin)["contributions"]))')"
echo "proof           $(printf '%s' "$PROOF" | python3 -c 'import json,sys; p=json.load(sys.stdin); print(json.dumps({k: p.get(k) for k in ("verified","valid","hashMatchesCanonical","signatureValid","valueEventHashValid")} | {"proofValid": (p.get("anchor") or {}).get("proofValid")}))')"

VERIFIED="$(printf '%s' "$PROOF" | jget "['verified']")"
[[ "$STATE" == "ANCHORED" ]] || fail "the value event is ${STATE}, not ANCHORED (is the signer up and funded? retry: POST /api/cryptobot/anchors?wait=true)"
[[ "$VERIFIED" == "True" ]] || fail "the proof is not verified"
log "PASS — ValueEvent anchored on devnet and verified"
