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

for tool in cargo-build-sbf solana; do
  command -v "$tool" >/dev/null || { echo "missing $tool: install the Solana CLI (https://docs.anza.xyz/cli/install)" >&2; exit 3; }
done

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

PROGRAM_ID="$(solana-keygen pubkey "$PROGRAM_KEYPAIR")"
echo "== deployed =="
solana program show --url "$CLUSTER_URL" "$PROGRAM_ID"
echo
echo "program id: $PROGRAM_ID"
echo "explorer:   https://explorer.solana.com/address/$PROGRAM_ID?cluster=devnet"
