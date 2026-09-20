#!/usr/bin/env bash
# KAN-570 — the devnet test wallet and the demo secrets, generated once, never committed.
#
#   scripts/demo/wallet-devnet.sh [--airdrop-sol N] [--no-airdrop]
#
# Creates, if missing, under $CRYPTOBOT_DEMO_HOME (default ~/.cryptobot-demo, mode 0700):
#   demo-wallet.json      the wallet the signer controls (64-byte solana-keygen layout)  — SECRET
#   rebalance-vault.json  the destination of the executable SOL leg                       — SECRET (devnet, holds what the demo sends)
#   validator.json        the validator's attestation key (moves no funds)                — SECRET
# and writes .env.demo (gitignored) next to docker-compose.demo.yml with the tokens, the JWT
# secret, the receipt signing key, the public keys and the paths the compose mounts read-only.
# Then it airdrops devnet SOL to the wallet (RPC requestAirdrop, rate-limited; the web faucet
# https://faucet.solana.com is the fallback) until it holds --airdrop-sol (default 2).
#
# Prints ONLY public keys and balances. Never a secret, never a key file. `solana-keygen` is
# used when installed; otherwise the keypair is generated with python3 + cryptography
# (Ed25519, same 64-byte layout), or with openssl.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "${HERE}/../.." && pwd)"
# shellcheck disable=SC1091
source "${HERE}/lib.sh"

AIRDROP_SOL=2
AIRDROP=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --airdrop-sol) AIRDROP_SOL="$2"; shift 2 ;;
    --no-airdrop) AIRDROP=0; shift ;;
    -h|--help) sed -n 2,20p "$0"; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
done

need python3; need curl
ENV_FILE="${PROJECT}/.env.demo"

umask 077
mkdir -p "$DEMO_HOME"
chmod 700 "$DEMO_HOME"

# keygen <path>: a 64-byte solana-keygen keypair (seed ‖ public key) as a JSON byte array.
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

for name in demo-wallet rebalance-vault validator; do
  f="${DEMO_HOME}/${name}.json"
  if [[ -f "$f" ]]; then
    log "${name}.json exists (kept): $(pubkey_of "$f")"
  else
    keygen "$f"
    log "${name}.json generated: $(pubkey_of "$f")"
  fi
done

WALLET="$(pubkey_of "${DEMO_HOME}/demo-wallet.json")"
VAULT="$(pubkey_of "${DEMO_HOME}/rebalance-vault.json")"
VALIDATOR_PUB="$(pubkey_of "${DEMO_HOME}/validator.json")"

# ---- .env.demo: generated once; existing values are kept so tokens stay stable across runs ----
rand_hex() { python3 -c "import secrets; print(secrets.token_hex($1))"; }
# 64-byte Ed25519 secret for the receipt signing key (seed ‖ pub), base64 — the layout ReceiptKeyConfig expects.
receipt_key_b64() {
  python3 - <<'PY'
import base64, json, subprocess, tempfile, os
try:
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives import serialization
    k = Ed25519PrivateKey.generate()
    seed = k.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption())
    pub = k.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
except ImportError:
    pem = subprocess.check_output(["openssl", "genpkey", "-algorithm", "ed25519"])
    seed = subprocess.check_output(["openssl", "pkey", "-outform", "DER"], input=pem)[-32:]
    pub = subprocess.check_output(["openssl", "pkey", "-pubout", "-outform", "DER"], input=pem)[-32:]
print(base64.b64encode(seed + pub).decode())
PY
}

declare -A CUR=()
if [[ -f "$ENV_FILE" ]]; then
  while IFS='=' read -r k v; do
    [[ -z "$k" || "$k" == \#* ]] && continue
    CUR["$k"]="$v"
  done < "$ENV_FILE"
fi
keep() { # keep <KEY> <default-if-missing>
  local k="$1" d="$2"
  if [[ -n "${CUR[$k]:-}" ]]; then printf '%s' "${CUR[$k]}"; else printf '%s' "$d"; fi
}

JWT_SECRET="$(keep JWT_SECRET "$(rand_hex 32)")"
SIGNER_TOKEN="$(keep SIGNER_TOKEN "$(rand_hex 24)")"
VALIDATOR_TOKEN="$(keep VALIDATOR_TOKEN "$(rand_hex 24)")"
DB_PASSWORD="$(keep CRYPTOBOT_DB_PASSWORD "$(rand_hex 16)")"
RECEIPT_KEY="$(keep CRYPTOBOT_RECEIPT_SIGNING_KEY "$(receipt_key_b64)")"
RECEIPT_SALT="$(keep CRYPTOBOT_RECEIPT_SALT_SECRET "$(rand_hex 32)")"
POLICY_HASH="$(keep VALIDATOR_POLICY_HASH "$(default_policy_hash)")"

cat > "$ENV_FILE" <<EOF
# Generated by scripts/demo/wallet-devnet.sh on $(date -Is). GITIGNORED — contains secrets.
# docker compose -f docker-compose.demo.yml --env-file .env.demo up -d --build

# --- devnet wallet (public halves only; the key files are mounted read-only into the containers) ---
DEMO_WALLET_ADDRESS=${WALLET}
CRYPTOBOT_REBALANCE_VAULT=${VAULT}
SIGNER_ALLOWED_DESTINATIONS=${VAULT}
SIGNER_VALIDATOR_PUBLIC_KEY=${VALIDATOR_PUB}
SIGNER_KEYPAIR_HOST_PATH=${DEMO_HOME}/demo-wallet.json
VALIDATOR_KEYPAIR_HOST_PATH=${DEMO_HOME}/validator.json
SIGNER_MAX_LAMPORTS=2000000000
# The signer/validator containers run as this uid so they can read the 0600 key files above.
DEMO_UID=$(id -u)

# --- shared secrets of this demo stack (NOT the ones of any Life Engine environment) ---
JWT_SECRET=${JWT_SECRET}
SIGNER_TOKEN=${SIGNER_TOKEN}
CRYPTOBOT_SIGNER_TOKEN=${SIGNER_TOKEN}
VALIDATOR_TOKEN=${VALIDATOR_TOKEN}
CRYPTOBOT_VALIDATOR_TOKEN=${VALIDATOR_TOKEN}
CRYPTOBOT_DB_USER=cryptobot
CRYPTOBOT_DB_PASSWORD=${DB_PASSWORD}
CRYPTOBOT_RECEIPT_KEY_ID=demo-$(date +%Y%m%d)
CRYPTOBOT_RECEIPT_SIGNING_KEY=${RECEIPT_KEY}
CRYPTOBOT_RECEIPT_SALT_SECRET=${RECEIPT_SALT}

# --- policy pin: H_R the validator must hold (defaults of cryptobot.policy.authorization) ---
VALIDATOR_POLICY_HASH=${POLICY_HASH}

# --- demo knobs (local only; never copy to UAT/PROD) ---
CRYPTOBOT_DEMO_PORT=$(keep CRYPTOBOT_DEMO_PORT 8091)
CRYPTOBOT_TIMELOCK_ESCALATED=$(keep CRYPTOBOT_TIMELOCK_ESCALATED 20s)
CRYPTOBOT_SOLANA_DEVNET_RPC=$(keep CRYPTOBOT_SOLANA_DEVNET_RPC "$DEVNET_RPC")
# Optional: a Life Engine Runtime on the host for the LLM advisor step (runtime PR #33). Unused by the E2E.
CRYPTOBOT_RUNTIME_BASE_URL=$(keep CRYPTOBOT_RUNTIME_BASE_URL http://host.docker.internal:8090)
EOF
chmod 600 "$ENV_FILE"
log ".env.demo written: $ENV_FILE (secrets inside; gitignored)"
log "wallet=${WALLET}"
log "vault=${VAULT}"
log "validator=${VALIDATOR_PUB}"
log "policy pin=${POLICY_HASH}"

# ---- airdrop ---------------------------------------------------------------------------------
if [[ "$AIRDROP" -eq 0 ]]; then
  exit 0
fi
RPC="${CUR[CRYPTOBOT_SOLANA_DEVNET_RPC]:-$DEVNET_RPC}"
target=$(python3 -c "print(int(${AIRDROP_SOL} * 1_000_000_000))")
bal="$(balance_lamports "$RPC" "$WALLET")"
log "balance: ${bal} lamports on ${RPC}"
attempt=0
while (( bal < target )); do
  attempt=$((attempt + 1))
  if (( attempt > 6 )); then
    warn "airdrop rate-limited. Fund ${WALLET} manually at https://faucet.solana.com (devnet) and re-run."
    exit 2
  fi
  want=$(( target - bal ))
  (( want > 2000000000 )) && want=2000000000
  if command -v solana >/dev/null 2>&1; then
    solana airdrop --url "$RPC" "$(python3 -c "print(${want}/1e9)")" "$WALLET" >/dev/null 2>&1 || true
  else
    if airdrop_confirmed "$RPC" "$WALLET" "$want"; then
      log "airdrop of ${want} lamports confirmed"
    else
      warn "requestAirdrop refused or not confirmed (devnet faucet is rate-limited: https://faucet.solana.com)"
    fi
  fi
  sleep 8
  bal="$(balance_lamports "$RPC" "$WALLET")"
  log "balance: ${bal} lamports"
done
log "wallet ${WALLET} holds ${bal} lamports (≥ ${AIRDROP_SOL} SOL). Ready."
