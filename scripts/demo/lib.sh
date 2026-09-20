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
default_policy_hash() {
  python3 - <<'PY'
import hashlib
canonical = ('{"allowed_assets":["SOL","USDC","USDT"],"autonomous_up_to_cents":10000,"daily_limit_cents":250000,'
             '"enabled_strategies":["REBALANCE"],"max_asset_exposure_bps":8000,"max_oracle_age_seconds":900,'
             '"max_slippage_bps":100,"max_trade_value_cents":50000,"schema_version":"1","second_agent_up_to_cents":25000,'
             '"version":"cryptobot-policy-v1"}')
print("sha256:" + hashlib.sha256(canonical.encode()).hexdigest())
PY
}
