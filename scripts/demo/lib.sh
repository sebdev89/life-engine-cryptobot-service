#!/usr/bin/env bash
# Shared helpers of the demo scripts. Sourced, never executed.
#
# Rules: the wallet key, the validator key and the tokens are SECRETS. They live in
# $CRYPTOBOT_DEMO_HOME (0700) and in .env.demo (gitignored). These helpers print public keys
# and balances only — never a secret, never a key file's content.

DEMO_HOME="${CRYPTOBOT_DEMO_HOME:-$HOME/.cryptobot-demo}"
DEVNET_RPC="${CRYPTOBOT_SOLANA_DEVNET_RPC:-https://api.devnet.solana.com}"

log()  { printf '\033[1;34m[demo]\033[0m %s\n' "$*" >&2; }
warn() { printf '\033[1;33m[demo]\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[1;31m[demo]\033[0m %s\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || fail "missing tool: $1"; }

# ---- HTTP against the service (shared by e2e-devnet.sh and run.sh) --------------------
# Globals the caller sets: BASE (service URL), TOKEN (bearer, never printed), CURL_OPTS (array,
# extra curl arguments, e.g. --resolve for a UAT host without public DNS).
# api <method> <path> [json-body] [extra curl args...]  → body, newline, HTTP status
api() {
  local m="$1" p="$2" b="${3:-}"; shift 3 || shift $#
  if [[ -n "$b" ]]; then
    curl -sS -m 150 "${CURL_OPTS[@]}" -X "$m" "${BASE}${p}" -H "Authorization: Bearer ${TOKEN}" -H 'content-type: application/json' -d "$b" -w '\n%{http_code}' "$@"
  else
    curl -sS -m 150 "${CURL_OPTS[@]}" -X "$m" "${BASE}${p}" -H "Authorization: Bearer ${TOKEN}" -w '\n%{http_code}' "$@"
  fi
}
# jget "<python index expression>"  ← JSON on stdin; prints the value or nothing (never fails the caller)
jget() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval('d'+sys.argv[1]))" "$1" 2>/dev/null || true; }
# split_status: RESP (body + status from api) → BODY and STATUS
split_status() { STATUS="${RESP##*$'\n'}"; BODY="${RESP%$'\n'*}"; }
step() { printf '\n\033[1;32m== %s\033[0m\n' "$*" >&2; }

# rpc <url> <method> <params-json>  → prints the JSON response
rpc() {
  curl -sS -m 30 "$1" -X POST -H 'content-type: application/json' \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$2\",\"params\":$3}"
}

# pubkey_of <keypair.json>  → base58 public key (last 32 bytes of the 64-byte solana-keygen layout).
# Reads the file locally and prints ONLY the public half.
pubkey_of() {
  python3 - "$1" <<'PY'
import json, sys
ALPH = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'
def b58(b):
    n = int.from_bytes(b, 'big'); s = ''
    while n:
        n, r = divmod(n, 58); s = ALPH[r] + s
    return '1' * (len(b) - len(b.lstrip(b'\0'))) + s
k = bytes(json.load(open(sys.argv[1])))
assert len(k) == 64, 'expected a 64-byte solana-keygen keypair'
print(b58(k[32:]))
PY
}

# keygen <path>: a 64-byte solana-keygen keypair (seed ‖ public key) as a JSON byte array, 0600. solana-keygen when
# installed (`solana-keygen new --no-bip39-passphrase`), else the same layout from python/openssl. Prints nothing.
keygen() {
  local out="$1"
  if command -v solana-keygen >/dev/null 2>&1; then
    solana-keygen new --no-bip39-passphrase --silent --outfile "$out" >/dev/null
  else
    python3 - "$out" <<'PY'
import json, sys
try:
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives import serialization
    k = Ed25519PrivateKey.generate()
    seed = k.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption())
    pub = k.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
except ImportError:
    import subprocess
    pem = subprocess.check_output(["openssl", "genpkey", "-algorithm", "ed25519"])
    der = subprocess.check_output(["openssl", "pkey", "-outform", "DER"], input=pem)
    pubder = subprocess.check_output(["openssl", "pkey", "-pubout", "-outform", "DER"], input=pem)
    seed, pub = der[-32:], pubder[-32:]
with open(sys.argv[1], "w") as f:
    json.dump(list(seed + pub), f, separators=(",", ":"))
PY
  fi
  chmod 600 "$out"
}

# balance_lamports <rpc-url> <pubkey>
balance_lamports() {
  rpc "$1" getBalance "[\"$2\", {\"commitment\": \"confirmed\"}]" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("result",{}).get("value", 0))'
}

# airdrop_confirmed <rpc-url> <pubkey> <lamports>  → requests one airdrop and waits until its signature
# is confirmed (or 40 s). Never asks twice for the same top-up: on a validator that is still warming up a
# balance poll lags and a naive retry loop lands N airdrops at once.
airdrop_confirmed() {
  local sig
  sig="$(rpc "$1" requestAirdrop "[\"$2\", $3]" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("result") or "")')"
  [[ -n "$sig" ]] || return 1
  for _ in $(seq 1 45); do
    local st
    st="$(rpc "$1" getSignatureStatuses "[[\"${sig}\"],{\"searchTransactionHistory\":true}]" \
      | python3 -c 'import json,sys; v=json.load(sys.stdin).get("result",{}).get("value",[None])[0]; print((v or {}).get("confirmationStatus") or "")')"
    [[ "$st" == "confirmed" || "$st" == "finalized" ]] && return 0
    sleep 2
  done
  return 1
}

# jwt_hs256 <secret> <subject-uuid> <email>  → a Life Engine-shaped HS256 token (RUNTIME_OPERATOR + RUNTIME_ADMIN), 1 h.
# Mirrors what life-engine-auth issues; only for the local demo where the service verifies the shared secret.
# TODO-PLATFORM KAN-? (propuesto — NO creado; state/proposals/auth-demo-mode-para-composes-de-vertical.md):
# this is Auth's token contract copied into the vertical. `run.sh --target uat` already logs in against
# the real Auth; the local compose would do the same once Auth ships an embeddable demo profile.
jwt_hs256() {
  python3 - "$1" "$2" "$3" <<'PY'
import base64, hmac, hashlib, json, sys, time
secret, sub, email = sys.argv[1].encode(), sys.argv[2], sys.argv[3]
def b64(b): return base64.urlsafe_b64encode(b).rstrip(b'=').decode()
now = int(time.time())
h = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(',', ':')).encode())
p = b64(json.dumps({"sub": sub, "email": email, "authorities": ["RUNTIME_OPERATOR", "RUNTIME_ADMIN"],
                    "iat": now, "exp": now + 3600}, separators=(',', ':')).encode())
sig = b64(hmac.new(secret, f"{h}.{p}".encode(), hashlib.sha256).digest())
print(f"{h}.{p}.{sig}")
PY
}

# H_R of the policy the demo runs with: the defaults of cryptobot.policy.authorization
# (application.yml) in the integer units of the schema. The same canonical string is pinned by
# DefaultPolicyHashParityTest (service) and PolicyStoreTest (validator). If you override any
# CRYPTOBOT_POLICY_* / VALIDATOR_POLICY_* value, re-pin VALIDATOR_POLICY_HASH from the hash the
# service logs (proposal_policy … policyHash=) — or leave it empty to run unpinned (WARN).
# default_policy_hash [strategies]: a comma list (default REBALANCE). an internal ticket: the demo runs with REBALANCE,POV_REWARD
# (Proof of Value payouts) — pinned by DefaultPolicyHashParityTest.demoPolicyWithPovRewardIsSortedAndStable.
default_policy_hash() {
  python3 - "${1:-REBALANCE}" <<'PY'
import hashlib, json, sys
strategies = sorted({s.strip() for s in sys.argv[1].split(",") if s.strip()})
canonical = ('{"allowed_assets":["SOL","USDC","USDT"],"autonomous_up_to_cents":10000,"daily_limit_cents":250000,'
             '"enabled_strategies":' + json.dumps(strategies, separators=(",", ":")) + ','
             '"max_asset_exposure_bps":8000,"max_oracle_age_seconds":900,'
             '"max_slippage_bps":100,"max_trade_value_cents":50000,"schema_version":"1","second_agent_up_to_cents":25000,'
             '"version":"cryptobot-policy-v1"}')
print("sha256:" + hashlib.sha256(canonical.encode()).hexdigest())
PY
}

# the Proof of Value identities of the demo that get a devnet wallet (~/.cryptobot-demo/pov-<id>.json) — sebas
# (HUMAN) too, so the payment to the human shows. wallet-devnet.sh generates them BEFORE writing .env.demo and adds their
# PUBLIC keys to SIGNER_ALLOWED_DESTINATIONS; pov-v1.sh registers them.
# shellcheck disable=SC2034  # read by wallet-devnet.sh and pov-v1.sh
POV_WALLET_IDS=(sebas dev-agent-17 cryptobot-001 review-agent-3 compute-node-8)
# The strategies the demo policy enables on BOTH sides (service and validator): POV_REWARD is the payouts' strategy.
# shellcheck disable=SC2034
DEMO_POLICY_STRATEGIES="REBALANCE,POV_REWARD"


# ---- Proof of Value (internal ticket…an internal ticket): shared by pov-v1.sh and pov-e2e.sh ------------------------
# They use api / split_status (BASE, TOKEN, CURL_OPTS set by the caller) and set RESP / STATUS / BODY.

# pov_identity_body <id> <wallet>: the POST /identities body of a seed identity.
pov_identity_body() {
  case "$1" in
    sebas)          printf '{"id":"sebas","kind":"HUMAN","displayName":"Sebastián","wallet":"%s"}' "$2" ;;
    dev-agent-17)   printf '{"id":"dev-agent-17","kind":"AGENT","displayName":"Dev Agent 17","wallet":"%s","ownerId":"sebas"}' "$2" ;;
    cryptobot-001)  printf '{"id":"cryptobot-001","kind":"AGENT","displayName":"CryptoBot 001","wallet":"%s","ownerId":"sebas","operatorId":"sebas"}' "$2" ;;
    review-agent-3) printf '{"id":"review-agent-3","kind":"AGENT","displayName":"Review Agent 3","wallet":"%s"}' "$2" ;;
    compute-node-8) printf '{"id":"compute-node-8","kind":"AGENT","displayName":"Compute Node 8","wallet":"%s","ownerId":"sebas"}' "$2" ;;
  esac
}

# pov_assets_json: the two seed knowledge assets (the content is hashed here; only the hash is registered).
pov_assets_json() {
  python3 - <<'PY'
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
}

# pov_seed_identities: POST /identities for every POV_WALLET_IDS entry (idempotent). The wallet is the public key of
# $DEMO_HOME/pov-<id>.json (generated here if missing); only the public key is sent.
pov_seed_identities() {
  local id kp wallet stored
  mkdir -p "$DEMO_HOME"; chmod 700 "$DEMO_HOME"
  for id in "${POV_WALLET_IDS[@]}"; do
    kp="${DEMO_HOME}/pov-${id}.json"
    [[ -f "$kp" ]] || { keygen "$kp"; log "keypair generated: ${kp} (not in the signer's allowlist until wallet-devnet.sh runs again)"; }
    wallet="$(pubkey_of "$kp")"
    RESP="$(api POST /api/cryptobot/identities "$(pov_identity_body "$id" "$wallet")")"; split_status
    [[ "$STATUS" == 201 || "$STATUS" == 200 ]] || fail "POST /identities ${id} → ${STATUS}: ${BODY}"
    stored="$(printf '%s' "$BODY" | jget "['wallet']")"
    [[ "$stored" == None ]] && stored=""
    if [[ -n "$wallet" && "$stored" != "$wallet" ]]; then
      warn "identity ${id} already has another wallet (${stored:-none}); the service never overwrites a wallet"
    fi
    log "identity ${id} ($(printf '%s' "$BODY" | jget "['kind']")) wallet=${stored:-none} → ${STATUS}"
  done
}

# pov_seed_assets: POST /knowledge-assets for each asset of pov_assets_json (idempotent).
pov_seed_assets() {
  local body
  while IFS= read -r body; do
    RESP="$(api POST /api/cryptobot/knowledge-assets "$body")"; split_status
    [[ "$STATUS" == 201 || "$STATUS" == 200 ]] || fail "POST /knowledge-assets → ${STATUS}: ${BODY}"
    log "asset $(printf '%s' "$BODY" | jget "['id']") ($(printf '%s' "$BODY" | jget "['kind']")) → ${STATUS}"
  done < <(pov_assets_json | python3 -c 'import json,sys; [print(json.dumps(a, separators=(",", ":"))) for a in json.load(sys.stdin)]')
}

# pov_rpc_url <env-file>: the devnet RPC the demo uses (environment, then the env file, then the public devnet).
pov_rpc_url() {
  local u="${CRYPTOBOT_SOLANA_DEVNET_RPC:-}"
  if [[ -z "$u" && -f "${1:-}" ]]; then u="$(sed -n 's/^CRYPTOBOT_SOLANA_DEVNET_RPC=//p' "$1" | tail -1)"; fi
  printf '%s\n' "${u:-$DEVNET_RPC}"
}

# pov_print_payouts ← a DistributionView or RevenueEventView on stdin: one line per payout (+ tx and explorer link).
pov_print_payouts() {
  python3 -c '
import json, sys
for p in json.load(sys.stdin)["payouts"]:
    print("  %-15s %-9s %10s lamports  wallet=%s" % (p["identityId"], p["status"], p["lamports"], p.get("wallet") or "-"))
    if p.get("txSignature"):
        print("  %-15s tx %s" % ("", p["txSignature"]))
        print("  %-15s %s" % ("", p.get("explorerUrl") or ""))
    if p.get("error"):
        print("  %-15s error: %s" % ("", p["error"]))'
}

# pov_print_revenue <http-status> ← a RevenueEventView on stdin: source, split, receipt, links (payouts: pov_print_payouts).
pov_print_revenue() {
  S="$1" python3 -c '
import json, os, sys
r = json.load(sys.stdin)
pol = r["policy"]
print("revenue event   %s → %s %s  source=%s:%s simulated=%s" % (r["id"], os.environ["S"], r["status"], r["source"]["kind"], r["source"]["ref"], r["simulated"]))
print("amount          %s lamports (%s)" % (r["amountLamports"], "SIMULATED economic result — not real profit" if r["simulated"] else "reported"))
print("policy          %s  contributor pool %s bps · protocol fee %s bps · rest retained" % (pol["name"], pol["revenueShareBps"], pol["protocolFeeBps"]))
print("split           pool=%s  fee=%s (recorded only)  retained=%s" % (r["contributorPoolLamports"], r["protocolFeeLamports"], r["retainedLamports"]))
print("receipt         %s (REVENUE_EVENT, anchored: %s)" % (r["receiptHash"], (r.get("anchor") or {}).get("txSignature")))
print("linked          " + " · ".join("%s (%s) share=%s" % (l["id"], l["title"], l.get("shareLamports")) for l in r["linkedValueEvents"]))'
}

# pov_print_treasury ← a TreasuryView on stdin.
pov_print_treasury() {
  python3 -c '
import json, sys
t = json.load(sys.stdin)
p = t["policies"]
print("  wallet            %s  on-chain balance=%s%s" % (t.get("wallet") or "-", t.get("onChainBalanceLamports"), ("  (" + t["balanceNote"] + ")") if t.get("balanceNote") else ""))
print("  income            %s lamports" % t["incomeLamports"])
print("  contributor paid  %s lamports (CONFIRMED)" % t["contributorPayoutsLamports"])
print("  protocol fee      %s lamports" % t["protocolFeeLamports"])
print("  retained          %s lamports" % t["retainedLamports"])
print("  compute cost      $%.2f (estimated; cost is not value)" % (t["computeCostMicroUsd"] / 1e6))
print("  policies          reward pool=%s  revenue share=%s bps  fee=%s bps  signer max=%s" % (p["rewardPoolLamports"], p["revenueShareBps"], p["protocolFeeBps"], p.get("signerMaxLamports")))
for e in t["recentEvents"][:8]:
    print("  %-8s %-37s %12s  %s  %s" % (e["kind"], e["id"], e["lamports"], e["at"], e.get("txSignature") or ""))'
}

# ---- secrets check (shared by run.sh and pov-e2e.sh since an earlier change) --------------------------
# secrets_scan <env-file> <file>...: every SECRET value of the env file must be absent from the files. Only keys named like a
# secret count (JWT_SECRET, *_TOKEN, *_PASSWORD, *_SIGNING_KEY, *_SALT_SECRET) — public values (wallet addresses, the policy
# hash, CRYPTOBOT_DB_USER) are never searched, and values under 12 characters neither (a short value false-positives on hashes).
# Prints the NAME of each leaked key (never the value) and "searched=<n> hits=<m>"; returns 0 only with 0 hits.
secrets_scan() {
  local env="$1" k v hits=0 n=0; shift
  [[ -f "$env" ]] || { echo "searched=0 hits=0"; return 0; }
  while IFS='=' read -r k v; do
    [[ "$k" =~ ^(JWT_SECRET|.*_TOKEN|.*_PASSWORD|.*_SIGNING_KEY|.*_SALT_SECRET)$ ]] || continue
    (( ${#v} >= 12 )) || continue
    n=$((n + 1))
    if grep -q -F -- "$v" "$@" 2>/dev/null; then echo "LEAK ${k}"; hits=$((hits + 1)); fi
  done < "$env"
  echo "searched=${n} hits=${hits}"
  (( hits == 0 ))
}

# read_summary <file> <assoc-array-name>: the key=value summary e2e-devnet.sh writes (--summary) into an associative array.
read_summary() {
  local -n _a="$2"; local k v
  while IFS='=' read -r k v; do [[ -n "$k" ]] && _a["$k"]="$v"; done < "$1"
}

# demo_token <env-file>: an HS256 demo token (jwt_hs256) minted with JWT_SECRET of the env file for the STABLE demo operator
# (uuid5 of demo@cryptobot.local — the subject ui-url.sh uses, so the UI shows the same data). Printed to the caller's
# capture only: never echo it. Fails without the env file or the secret.
demo_token() {
  local secret sub
  [[ -f "$1" ]] || fail "no $1 and no CRYPTOBOT_DEMO_TOKEN"
  secret="$(sed -n 's/^JWT_SECRET=//p' "$1" | tail -1)"
  [[ -n "$secret" ]] || fail "JWT_SECRET missing in $1"
  sub="$(python3 -c 'import uuid; print(uuid.uuid5(uuid.NAMESPACE_DNS, "demo@cryptobot.local"))')"
  jwt_hs256 "$secret" "$sub" "demo@cryptobot.local"
}
