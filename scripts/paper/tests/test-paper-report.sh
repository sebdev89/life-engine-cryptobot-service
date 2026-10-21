#!/usr/bin/env bash
# offline checks of scripts/paper/paper-report.sh (KAN-1217): the psql output in tests/fixtures/*.psv is parsed
# and rendered with a fixed "now", every number checked by hand; no kubectl, no Jira (--dry-run, or a fake JIRA_CLI).
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$(cd "${HERE}/.." && pwd)/paper-report.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
pass=0; failn=0
ok()   { pass=$((pass + 1)); printf '  ✓ %s\n' "$*"; }
bad()  { failn=$((failn + 1)); printf '  ✗ %s\n' "$*"; }
check() { # check "<name>" <expected-rc> <cmd…>   (stdout+stderr captured in $OUT)
  local name="$1" want="$2"; shift 2
  OUT="$("$@" 2>&1)"; local rc=$?
  if [[ "$rc" -eq "$want" ]]; then ok "${name} (rc ${rc})"; else bad "${name}: rc ${rc}, expected ${want}: $(printf '%s' "$OUT" | tail -3)"; fi
}
has() { printf '%s' "$OUT" | grep -q -F -- "$1" && ok "has '$1'" || bad "lacks '$1'"; }

echo "arguments"
check "--help exits 0" 0 "$SCRIPT" --help
has "TODO(KAN-1214)"
check "unknown argument" 1 "$SCRIPT" --bogus

echo "report from fixtures (now = 2026-10-21 18:05Z, last candle 17:00Z)"
export PAPER_REPORT_FIXTURES="${HERE}/fixtures"
check "dry run exits 0" 0 env PAPER_REPORT_NOW=2026-10-21T18:05:00Z "$SCRIPT" --dry-run
# the 17:00 candle closes at 18:00: at 18:05 it closed 5 min ago
has "última vela 2026-10-21 17:00Z (día 1 de 14–28) · cerró hace 5 min"
# band: 1000 → 1050 → 1000: max DD (1050−1000)/1050 = 4.76 %, worst day 2026-10-21 = 1000/1050 − 1
has "band-50-500 | 1000.00 | +0.00 | +0.00% | 4.76% | -4.76% (2026-10-21) | 0 | 0.00 | sí | no"
# hold-sol: 1000 → 1100 → 990: −1 %, DD 10 %, worst day 990/1100 − 1 = −10 %
has "hold-sol | 990.00 | -10.00 | -1.00% | 10.00% | -10.00% (2026-10-21) | 0 | 0.00 | — | no"
# a tie with hold-usdc (1000 = 1000) does not beat it; a benchmark is not compared with itself
has "hold-usdc | 1000.00 | +0.00 | +0.00% | 0.00% | +0.00% (2026-10-20) | 0 | 0.00 | sí | —"
has "dca-50-24 | 999.75 | -0.25 | -0.02% | 0.50% | -0.50% (2026-10-21) | 1 | 0.25 | sí | no"
has "Coinbase vs Jupiter: 3 chequeos, 2 con ambos precios, desvío medio |2.50| bps, máximo |4| bps."
printf '%s' "$OUT" | grep -q 'NO avanza' && bad "fresh candle flagged as stale" || ok "a candle closed 5 min ago is not flagged"

echo "stale candle and end of window"
# the alarm is > 2 h since the last applied candle closed: one late candle (an Exchange hiccup) is tolerated
check "1 h 59 after the close" 0 env PAPER_REPORT_NOW=2026-10-21T19:59:00Z "$SCRIPT" --dry-run
printf '%s' "$OUT" | grep -q 'NO avanza' && bad "flagged at 119 min" || ok "119 min after the close is not flagged"
check "3 h after the close" 0 env PAPER_REPORT_NOW=2026-10-21T21:00:00Z "$SCRIPT" --dry-run
has "cerró hace 180 min ⚠ el job NO avanza"
cat > "${TMP}/jira" <<'EOF'
import os, sys
open(os.environ["FAKE_JIRA_OUT"], "w").write(" ".join(sys.argv[1:]))
EOF
check "posts to KAN-1217 through JIRA_CLI" 0 env PAPER_REPORT_NOW=2026-10-21T18:05:00Z JIRA_CLI="${TMP}/jira" FAKE_JIRA_OUT="${TMP}/posted" "$SCRIPT"
grep -q '^comment KAN-1217 REPORTE PAPER TRADING' "${TMP}/posted" 2>/dev/null && ok "comment goes to KAN-1217" || bad "no comment to KAN-1217"
rm -f "${TMP}/posted"
check "after 2026-11-24 it does not comment" 0 env PAPER_REPORT_NOW=2026-11-25T09:13:00Z JIRA_CLI="${TMP}/jira" FAKE_JIRA_OUT="${TMP}/posted" "$SCRIPT"
[[ ! -e "${TMP}/posted" ]] && ok "nothing posted after the window" || bad "posted after the window"

echo "no portfolios yet"
mkdir -p "${TMP}/empty"; : > "${TMP}/empty/portfolios.psv"; : > "${TMP}/empty/ticks.psv"; echo '0|0||' > "${TMP}/empty/gap.psv"
check "empty tables" 0 env PAPER_REPORT_FIXTURES="${TMP}/empty" PAPER_REPORT_NOW=2026-10-21T18:05:00Z "$SCRIPT" --dry-run
has "sin portfolios"

echo
echo "test-paper-report: ${pass} ok, ${failn} failed"
(( failn == 0 ))
