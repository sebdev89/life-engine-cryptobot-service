#!/usr/bin/env bash
# test-deploy-devnet.sh — los guards de scripts/deploy-devnet.sh, sin CLI de Solana y sin gastar
# nada (KAN-752).
#
# El deploy es un gate humano, cuesta ~2 SOL de devnet y NO es idempotente: un intento que se queda
# sin fondos a mitad deja un buffer con SOL atrapado que hay que cerrar a mano. Así que lo que se
# prueba acá es que el script se niegue ANTES de gastar, y que el program id se imprima antes del
# deploy y no después.
#
# Las herramientas se falsean con binarios en el PATH: `solana`, `solana-keygen` y `cargo-build-sbf`.
# El falso `solana` anota si alguien le pidió `program deploy`, que es la afirmación central de
# `--check`: valida todo y no despliega.
set -uo pipefail
AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$AQUI/../deploy-devnet.sh"
PASADAS=0; FALLADAS=0
t_ok()   { PASADAS=$((PASADAS+1)); printf '  \033[32m✓\033[0m %s\n' "$*"; }
t_fail() { FALLADAS=$((FALLADAS+1)); printf '  \033[31m✗\033[0m %s\n' "$*"; }
rc_es()  { [[ "$1" == "$2" ]] && t_ok "$3" || t_fail "$3 (rc esperado $2, obtenido $1)"; }
tiene()  { [[ "$1" == *"$2"* ]] && t_ok "$3" || { t_fail "$3"; printf '      no contiene: %q\n' "$2"; }; }

[[ -x "$SCRIPT" || -r "$SCRIPT" ]] || { echo "no existe $SCRIPT"; exit 1; }
bash -n "$SCRIPT" && t_ok "sintaxis válida" || t_fail "sintaxis inválida"

TMP="$(mktemp -d)"
PROG_DIR="$(cd "$AQUI/../.." && pwd)"
SO_REAL="$PROG_DIR/target/deploy/intent_authority.so"
HABIA_SO=0; [[ -f "$SO_REAL" ]] && HABIA_SO=1
limpiar() {
    rm -rf "$TMP"
    # Sólo se borra el artefacto si NO existía antes: un build real del operador no se toca.
    (( HABIA_SO )) || rm -f "$SO_REAL"
}
trap limpiar EXIT
BIN="$TMP/bin"; mkdir -p "$BIN"
printf 'keypair\n' > "$TMP/payer.json"; printf 'keypair\n' > "$TMP/program.json"

# ── los binarios falsos ──────────────────────────────────────────────────────────────────────
fakes() {  # <saldo> [--no-keygen]
    rm -f "$BIN"/*
    cat > "$BIN/solana" <<EOF
#!/usr/bin/env bash
if [[ "\$1" == balance ]]; then echo "$1 SOL"; exit 0; fi
if [[ "\$1" == program && "\$2" == deploy ]]; then echo deploy >> "$TMP/llamadas"; exit 0; fi
if [[ "\$1" == program && "\$2" == show ]]; then echo "Program Id: FakeId"; exit 0; fi
exit 0
EOF
    cat > "$BIN/cargo-build-sbf" <<EOF
#!/usr/bin/env bash
mkdir -p "$PROG_DIR/target/deploy" && printf 'so\n' > "$PROG_DIR/target/deploy/intent_authority.so"
exit 0
EOF
    if [[ "${2:-}" != --no-keygen ]]; then
        printf '#!/usr/bin/env bash\necho FakeProgramId1111111111111111111111111111\n' > "$BIN/solana-keygen"
    fi
    chmod +x "$BIN"/*
    rm -f "$TMP/llamadas"
}
corre() { PATH="$BIN:$PATH" PAYER="$TMP/payer.json" PROGRAM_KEYPAIR="$TMP/program.json" bash "$SCRIPT" "$@" 2>&1; }

echo "── se niega fuera de devnet ──"
fakes 10
OUT="$(PATH="$BIN:$PATH" PAYER="$TMP/payer.json" PROGRAM_KEYPAIR="$TMP/program.json" \
      CLUSTER_URL=https://api.mainnet-beta.solana.com bash "$SCRIPT" 2>&1)"; rc_es $? 2 "mainnet → exit 2"
tiene "$OUT" "human gate" "y dice que es un gate humano"
[[ ! -f "$TMP/llamadas" ]] && t_ok "no llamó a program deploy" || t_fail "desplegó igual"

echo "── flag desconocido ──"
fakes 10; OUT="$(corre --lo-que-sea)"; rc_es $? 2 "flag desconocido → exit 2"

echo "── falta una herramienta que el script USA ──"
# solana-keygen estaba usado y no verificado: sin esto el script fallaba DESPUÉS del deploy, con el
# SOL gastado y sin haber impreso el program id.
fakes 10 --no-keygen; OUT="$(corre --check)"; rc_es $? 3 "sin solana-keygen → exit 3"
tiene "$OUT" "solana-keygen" "y lo nombra"
[[ ! -f "$TMP/llamadas" ]] && t_ok "y no desplegó" || t_fail "desplegó sin la herramienta"

echo "── keypair ilegible ──"
fakes 10
OUT="$(PATH="$BIN:$PATH" PAYER="$TMP/no-existe" PROGRAM_KEYPAIR="$TMP/program.json" bash "$SCRIPT" --check 2>&1)"
rc_es $? 3 "payer ilegible → exit 3"
tiene "$OUT" "cannot read keypair" "y lo dice"

echo "── saldo insuficiente: se niega ANTES de gastar ──"
fakes 0.5; OUT="$(corre --check)"; rc_es $? 5 "saldo 0.5 < 2 → exit 5"
tiene "$OUT" "faucet.solana.com" "y da el faucet"
[[ ! -f "$TMP/llamadas" ]] && t_ok "no dejó un buffer a medias: no desplegó" || t_fail "desplegó con saldo insuficiente"
fakes 1.999; OUT="$(corre --check)"; rc_es $? 5 "1.999 también se niega (el límite no es entero)"

echo "── --check con todo bien: valida y NO despliega ──"
fakes 10; OUT="$(corre --check)"; rc_es $? 0 "--check → exit 0"
tiene "$OUT" "nothing deployed" "dice que no desplegó nada"
[[ ! -f "$TMP/llamadas" ]] && t_ok "y de verdad no llamó a program deploy" || t_fail "--check desplegó"
tiene "$OUT" "payer balance: 10 SOL" "informa el saldo leído"

echo "── el program id se imprime ANTES del deploy ──"
# Si el deploy falla a mitad, el operador necesita el program id para cerrar el buffer. Antes se
# calculaba después del deploy, así que en ese caso no lo veía nunca.
fakes 10; OUT="$(corre)"; rc_es $? 0 "deploy completo (falso) → exit 0"
linea_id="$(grep -n 'program id (from the program keypair)' <<<"$OUT" | head -1 | cut -d: -f1)"
linea_dep="$(grep -n '== deploy →' <<<"$OUT" | head -1 | cut -d: -f1)"
if [[ -n "$linea_id" && -n "$linea_dep" && "$linea_id" -lt "$linea_dep" ]]; then
    t_ok "el program id aparece antes de la línea del deploy"
else
    t_fail "el program id no se imprime antes del deploy (id=$linea_id deploy=$linea_dep)"
fi
[[ -f "$TMP/llamadas" ]] && t_ok "sin --check sí despliega" || t_fail "no desplegó cuando debía"

printf '\ntest-deploy-devnet: %d ok, %d fallos\n' "$PASADAS" "$FALLADAS"
(( FALLADAS == 0 ))
