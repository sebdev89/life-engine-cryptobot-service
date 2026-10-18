#!/usr/bin/env bash
# Build the program for SBF and deploy it to **devnet**. Devnet only: the script
# refuses any other cluster. Needs the Solana CLI (`cargo build-sbf`, `solana`), a funded devnet
# keypair for the deploy fee (~2 SOL, faucet: https://faucet.solana.com) and a program keypair.
#
# Keys never enter the repo: pass them by path. The program keypair is the upgrade authority's
# proof — keep it with the same care as a wallet key (outside the workspace, no backups in git).
#
#   PAYER=~/.config/solana/devnet-deployer.json \
#   PROGRAM_KEYPAIR=~/.cryptobot-demo/intent-authority-devnet.json \
#   bash programs/intent-authority/scripts/deploy-devnet.sh
#
# Prints the program id to configure as `cryptobot.authority.program-id` (proposal: wiring is the
# next issue; this one ships the program and the client). Everything outside devnet is a human
# decision (Sebastián), not a flag of this script.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER_URL="${CLUSTER_URL:-https://api.devnet.solana.com}"
PAYER="${PAYER:?set PAYER=<path to funded devnet keypair>}"
PROGRAM_KEYPAIR="${PROGRAM_KEYPAIR:?set PROGRAM_KEYPAIR=<path to program keypair (solana-keygen new -o …)>}"

case "$CLUSTER_URL" in
  *devnet*) ;;
  *) echo "refusing: CLUSTER_URL=$CLUSTER_URL is not devnet (mainnet/testnet are a human gate)" >&2; exit 2 ;;
esac

# `--check` valida todo lo que se puede validar sin gastar nada y sale. El deploy es un gate humano
# que cuesta ~2 SOL de devnet y no es idempotente, así que conviene poder ensayarlo (KAN-752).
CHECK_ONLY=0
case "${1:-}" in
  --check) CHECK_ONLY=1 ;;
  "") ;;
  *) echo "usage: $0 [--check]" >&2; exit 2 ;;
esac

# `solana-keygen` estaba USADO pero no verificado: si faltaba, el script fallaba DESPUÉS del deploy
# —con el SOL ya gastado y sin imprimir el program id, que es justo lo que el operador necesita—.
for tool in cargo-build-sbf solana solana-keygen; do
  command -v "$tool" >/dev/null || { echo "missing $tool: install the Solana CLI (https://docs.anza.xyz/cli/install)" >&2; exit 3; }
done

for kp in "$PAYER" "$PROGRAM_KEYPAIR"; do
  [[ -r "$kp" ]] || { echo "cannot read keypair: $kp" >&2; exit 3; }
done

# El program id se calcula ANTES del deploy. Si el deploy falla a mitad de camino, el operador igual
# sabe a qué dirección quedó asociado el buffer y puede cerrarlo (`solana program close`).
PROGRAM_ID="$(solana-keygen pubkey "$PROGRAM_KEYPAIR")"
echo "program id (from the program keypair): $PROGRAM_ID"

# Saldo del payer. Un deploy que se queda sin fondos a mitad deja un buffer con SOL atrapado y hace
# falta cerrarlo a mano; negarse antes es más limpio que limpiar después.
MIN_SOL="${MIN_SOL:-2}"
BALANCE="$(solana balance --url "$CLUSTER_URL" --keypair "$PAYER" 2>/dev/null | awk '{print $1}')"
if [[ -z "$BALANCE" ]]; then
  echo "could not read the payer balance from $CLUSTER_URL" >&2; exit 3
fi
echo "payer balance: $BALANCE SOL (need ~$MIN_SOL)"
if awk -v b="$BALANCE" -v m="$MIN_SOL" 'BEGIN { exit !(b < m) }'; then
  echo "refusing: payer has $BALANCE SOL, less than $MIN_SOL. Faucet: https://faucet.solana.com" >&2
  exit 5
fi

if (( CHECK_ONLY )); then
  echo "== --check: tools, keypairs, cluster and balance are fine; nothing deployed =="
  exit 0
fi

echo "== build (SBF) =="
( cd "$HERE" && cargo build-sbf )
SO="$HERE/target/deploy/intent_authority.so"
[[ -f "$SO" ]] || { echo "no artifact at $SO" >&2; exit 4; }
sha256sum "$SO"

echo "== deploy → $CLUSTER_URL =="
solana program deploy \
  --url "$CLUSTER_URL" \
  --keypair "$PAYER" \
  --program-id "$PROGRAM_KEYPAIR" \
  "$SO"

echo "== deployed =="
solana program show --url "$CLUSTER_URL" "$PROGRAM_ID"
echo
echo "program id: $PROGRAM_ID"
echo "explorer:   https://explorer.solana.com/address/$PROGRAM_ID?cluster=devnet"
