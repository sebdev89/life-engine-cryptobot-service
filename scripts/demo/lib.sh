#!/usr/bin/env bash
# Shared helpers of the demo scripts (KAN-570). Sourced, never executed.
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

# ---- HTTP against the service (KAN-575: shared by e2e-devnet.sh and run.sh) --------------------
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
# default_policy_hash [strategies]: a comma list (default REBALANCE). KAN-822: the demo runs with REBALANCE,POV_REWARD
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

# KAN-822: the Proof of Value identities of the demo that get a devnet wallet (~/.cryptobot-demo/pov-<id>.json) — sebas
# (HUMAN) too, so the payment to the human shows. wallet-devnet.sh generates them BEFORE writing .env.demo and adds their
# PUBLIC keys to SIGNER_ALLOWED_DESTINATIONS; pov-v1.sh registers them.
# shellcheck disable=SC2034  # read by wallet-devnet.sh and pov-v1.sh
POV_WALLET_IDS=(sebas dev-agent-17 cryptobot-001 review-agent-3 compute-node-8)
# The strategies the demo policy enables on BOTH sides (service and validator): POV_REWARD is the payouts' strategy.
# shellcheck disable=SC2034
DEMO_POLICY_STRATEGIES="REBALANCE,POV_REWARD"

