#!/usr/bin/env bash
# gate-devnet.sh — prepara y ENSAYA el gate del deploy a devnet en un solo comando (KAN-752).
#
#   bash programs/intent-authority/scripts/gate-devnet.sh            # prepara y ensaya, NO despliega
#   bash programs/intent-authority/scripts/gate-devnet.sh --deploy   # despliega de verdad
#
# ── Por qué existe ───────────────────────────────────────────────────────────────────────────
# El gate original eran seis comandos con rutas largas y continuaciones de línea. Al pegarlos, dos
# strings se truncaron (`deploy-devne`, `life-engine-cryptobot-serv`) y el `export PATH` quedó afuera,
# así que fallaron cinco de seis por el pegue y no por el sistema. Un gate que depende de pegar
# líneas largas sin cortar no es un gate: es una trampa. Esto es una línea corta.
#
# ── Qué hace y qué NO hace ───────────────────────────────────────────────────────────────────
# Crea la keypair del programa sólo si no existe, y NUNCA la sobreescribe: esa clave ES la autoridad
# de upgrade del programa desplegado, y perderla significa un programa que nadie puede volver a
# actualizar. No toca `demo-wallet.json`, cuyo saldo decide PASS/SKIP en el smoke de UAT.
# Sin `--deploy` no despliega nada.
set -Eeuo pipefail

export PATH="${SOLANA_BIN:-$HOME/.local/share/solana/install/active_release/bin}:$PATH"
AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLAVES="${LE_CB_KEYS:-$HOME/.cryptobot-demo}"
PROGRAMA="$CLAVES/intent-authority-devnet.json"
DEPLOYER="$CLAVES/devnet-deployer.json"
FONDEO="$CLAVES/rebalance-vault.json"      # el vault del demo, 6+ SOL de devnet
URL="${CLUSTER_URL:-https://api.devnet.solana.com}"
MIN_SOL="${MIN_SOL:-2}"
FONDEAR_SOL="${FONDEAR_SOL:-3}"
DEPLOY=0
case "${1:-}" in
    --deploy) DEPLOY=1 ;;
    "") ;;
    *) echo "uso: $0 [--deploy]" >&2; exit 2 ;;
esac

verde=$'\033[32m'; rojo=$'\033[31m'; ama=$'\033[33m'; gris=$'\033[90m'; reset=$'\033[0m'
ok()  { printf '  %s✓%s %s\n' "$verde" "$reset" "$*"; }
avi() { printf '  %s!%s %s\n' "$ama" "$reset" "$*"; }
die() { printf '  %s✗%s %s\n' "$rojo" "$reset" "$*" >&2; exit 1; }

echo "gate-devnet  ($(date -Is))"
echo
echo "1 · TOOLCHAIN"
for t in solana solana-keygen cargo-build-sbf; do
    command -v "$t" >/dev/null || die "falta $t. El instalador lo pone en \$HOME/.local/share/solana/…; si instalaste en otro lado: SOLANA_BIN=<ruta> $0"
done
ok "solana $(solana --version | head -1 | awk '{print $2}') · cargo-build-sbf $(cargo-build-sbf --version 2>/dev/null | head -1 | awk '{print $2}')"

echo
echo "2 · KEYPAIRS"
mkdir -p "$CLAVES"; chmod 700 "$CLAVES"
# `solana-keygen new` sin --force ya se niega si el archivo existe, pero se chequea acá para que el
# mensaje explique POR QUÉ no se sobreescribe. Perder esta clave = programa sin autoridad de upgrade.
if [[ -f "$PROGRAMA" ]]; then
    ok "keypair del programa: ya existe, NO se toca (es la autoridad de upgrade)"
else
    solana-keygen new --no-bip39-passphrase --silent -o "$PROGRAMA" >/dev/null
    chmod 600 "$PROGRAMA"
    ok "keypair del programa: creada"
fi
if [[ -f "$DEPLOYER" ]]; then
    ok "keypair del deployer: ya existe"
else
    solana-keygen new --no-bip39-passphrase --silent -o "$DEPLOYER" >/dev/null
    chmod 600 "$DEPLOYER"
    ok "keypair del deployer: creada"
fi
PROGRAM_ID="$(solana-keygen pubkey "$PROGRAMA")"
DEPLOYER_ADDR="$(solana-keygen pubkey "$DEPLOYER")"
printf '  %sprogram id: %s%s\n' "$gris" "$PROGRAM_ID" "$reset"
printf '  %sdeployer:   %s%s\n' "$gris" "$DEPLOYER_ADDR" "$reset"

echo
echo "3 · FONDEO DEL DEPLOYER"
saldo() { solana balance --url "$URL" "$1" 2>/dev/null | awk '{print $1}'; }
B="$(saldo "$DEPLOYER_ADDR")"; B="${B:-0}"
falta() { awk -v b="$1" -v m="$2" 'BEGIN { exit !(b < m) }'; }
if falta "$B" "$MIN_SOL"; then
    avi "el deployer tiene $B SOL y hacen falta ~$MIN_SOL"
    # Se fondea desde el vault del demo, NO desde demo-wallet.json: el saldo de esa wallet es lo que
    # decide PASS/SKIP en el smoke de UAT y gastarla rompería esa evidencia.
    if [[ -r "$FONDEO" ]]; then
        V="$(saldo "$(solana-keygen pubkey "$FONDEO")")"; V="${V:-0}"
        if falta "$V" "$FONDEAR_SOL"; then
            die "el vault tiene $V SOL: no alcanza para transferir $FONDEAR_SOL. Faucet: https://faucet.solana.com (dirección del deployer arriba)"
        fi
        echo "  transfiriendo $FONDEAR_SOL SOL del vault del demo al deployer (devnet)…"
        solana transfer --url "$URL" --keypair "$FONDEO" --allow-unfunded-recipient \
            "$DEPLOYER_ADDR" "$FONDEAR_SOL" >/dev/null
        B="$(saldo "$DEPLOYER_ADDR")"; B="${B:-0}"
        ok "deployer: $B SOL"
    else
        die "no encuentro $FONDEO para fondear. Usá el faucet con la dirección del deployer de arriba: https://faucet.solana.com"
    fi
else
    ok "deployer: $B SOL (alcanza)"
fi

echo
echo "4 · ENSAYO (--check: valida todo y no despliega)"
PAYER="$DEPLOYER" PROGRAM_KEYPAIR="$PROGRAMA" CLUSTER_URL="$URL" MIN_SOL="$MIN_SOL" \
    bash "$AQUI/deploy-devnet.sh" --check

echo
if (( ! DEPLOY )); then
    echo "5 · LISTO PARA DESPLEGAR"
    printf '  %sNada se desplegó todavía. Para el deploy real, una línea:%s\n' "$gris" "$reset"
    printf '      bash %s --deploy\n' "${BASH_SOURCE[0]}"
    exit 0
fi
echo "5 · DEPLOY REAL"
PAYER="$DEPLOYER" PROGRAM_KEYPAIR="$PROGRAMA" CLUSTER_URL="$URL" MIN_SOL="$MIN_SOL" \
    bash "$AQUI/deploy-devnet.sh"
echo
printf '  %sSiguiente: poner el program id en la config del servicio%s\n' "$gris" "$reset"
printf '      CRYPTOBOT_AUTHORITY_ENABLED=true\n'
printf '      CRYPTOBOT_AUTHORITY_PROGRAM_ID=%s\n' "$PROGRAM_ID"
