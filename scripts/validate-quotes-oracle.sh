#!/usr/bin/env bash
# manual, dev-only validation of /api/cryptobot/quotes/{asset} against criptos.com.ar.
#
# criptos.com.ar's API is neither public nor documented (Cloudflare, no ToS): it is NEVER called from
# production code. This script is the "oracle" step of the issue's acceptance criterion: for BTC and
# USDT, our ask/bid per exchange must be within ±0.5 % of what the oracle shows for the same exchange.
# Only exchanges present on both sides are compared (today: ripio; belo is on the oracle but not here).
#
# Usage: TOKEN=<life-engine JWT> scripts/validate-quotes-oracle.sh [BASE_URL] [ASSET ...]
set -euo pipefail

BASE="${1:-http://localhost:8091}"
shift || true
ASSETS=("${@:-BTC USDT}")
[[ ${#ASSETS[@]} -eq 1 && "${ASSETS[0]}" == *" "* ]] && read -r -a ASSETS <<<"${ASSETS[0]}"
ORACLE="https://criptos.com.ar/api"
TOL_PCT="${TOL_PCT:-0.5}"

if [[ -z "${TOKEN:-}" ]]; then
  echo "TOKEN (Life Engine JWT with access to /api/cryptobot/**) is required" >&2
  exit 2
fi
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }

status=0
for asset in "${ASSETS[@]}"; do
  ours="$(curl -sf -H "Authorization: Bearer ${TOKEN}" "${BASE}/api/cryptobot/quotes/${asset}")"
  echo "== ${asset}  (generatedAt $(jq -r .generatedAt <<<"${ours}"))"
  jq -r '.ranking[] | "  ours   \(.exchange)\task=\(.ask)\tbid=\(.bid)\tspread=\(.spreadPct)%\(if .stale then " STALE" else "" end)"' <<<"${ours}"
  jq -r '.unavailable[] | "  ours   \(.exchange)\tUNAVAILABLE \(.reason): \(.detail)"' <<<"${ours}"

  for ex in $(jq -r '.ranking[].exchange' <<<"${ours}"); do
    # The oracle is keyed by exchange name; pairs look like "BTC_ARS" with {ask,bid,feeArsAsk,feeArsBid,spread}.
    oracle="$(curl -s --max-time 10 "${ORACLE}/${ex}" || true)"
    if ! jq -e . >/dev/null 2>&1 <<<"${oracle}"; then
      echo "  oracle ${ex}\tno JSON (404/blocked) — not comparable"
      continue
    fi
    o_ask="$(jq -r --arg p "${asset}_ARS" '.[$p].ask // .[($p|ascii_downcase)].ask // empty' <<<"${oracle}")"
    o_bid="$(jq -r --arg p "${asset}_ARS" '.[$p].bid // .[($p|ascii_downcase)].bid // empty' <<<"${oracle}")"
    if [[ -z "${o_ask}" || -z "${o_bid}" ]]; then
      echo "  oracle ${ex}\tpair ${asset}_ARS not found — not comparable"
      continue
    fi
    m_ask="$(jq -r --arg e "${ex}" '.ranking[] | select(.exchange==$e) | .ask' <<<"${ours}")"
    m_bid="$(jq -r --arg e "${ex}" '.ranking[] | select(.exchange==$e) | .bid' <<<"${ours}")"
    d_ask="$(awk -v a="${m_ask}" -v b="${o_ask}" 'BEGIN{printf "%.3f", (a-b)/b*100}')"
    d_bid="$(awk -v a="${m_bid}" -v b="${o_bid}" 'BEGIN{printf "%.3f", (a-b)/b*100}')"
    ok="$(awk -v x="${d_ask}" -v y="${d_bid}" -v t="${TOL_PCT}" 'BEGIN{print ((x<0?-x:x)<=t && (y<0?-y:y)<=t) ? "OK" : "OUT_OF_TOLERANCE"}')"
    [[ "${ok}" == "OK" ]] || status=1
    printf '  oracle %s\task=%s\tbid=%s\tΔask=%s%%\tΔbid=%s%%\t%s\n' "${ex}" "${o_ask}" "${o_bid}" "${d_ask}" "${d_bid}" "${ok}"
  done
done
exit "${status}"
