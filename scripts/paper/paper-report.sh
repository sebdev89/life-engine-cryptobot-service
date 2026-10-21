#!/usr/bin/env bash
# paper-report.sh — weekly report of the CryptoBot v3 paper-trading window (KAN-1217).
#
#   scripts/paper/paper-report.sh            build the report and post it as a comment on KAN-1217
#   scripts/paper/paper-report.sh --dry-run  only print it
#
# Read-only against k8s-uat: three SELECTs on life_engine_cryptobot in the cluster's Postgres
# (kubectl exec postgres-0 … psql -c 'select …'). No user token, no port-forward, nothing written to the
# cluster; the only write is the Jira comment. Deterministic: no LLM, same rows → same text.
#
# Per portfolio: equity, P&L, return, max drawdown, worst UTC day (that day's last close against the
# previous day's), trades, costs, and whether it beats hold-sol / hold-usdc over the SAME window (same
# started_at, same last candle). Plus: how long ago the last applied candle CLOSED (> 2 h = the job is not
# advancing; the job applies a candle within one interval, 5 min, of its close) and the
# Coinbase-vs-Jupiter gap. Window: day 0 2026-10-20; minimum end 2026-11-03, maximum 2026-11-17.
#
# TODO(KAN-1214): it reads as `life`, the cluster's Postgres superuser, even if only to SELECT. Switch
# to the read-only role when KAN-1214 creates it (PAPER_REPORT_DB_USER). Creating roles is a credentials gate.
#
# Environment: KUBE_CONTEXT (default le-uat) · PAPER_REPORT_DB_USER (default life) · JIRA_CLI (default the
# workspace jira.py) · PAPER_REPORT_ISSUE (default KAN-1217). For tests: PAPER_REPORT_FIXTURES=<dir> reads
# portfolios.psv, ticks.psv and gap.psv instead of querying the cluster, PAPER_REPORT_NOW=<ISO-8601 UTC>
# fixes "now". Exit: 0 posted or printed (or the window is over) · 1 a query or the Jira comment failed.
set -euo pipefail

CTX="${KUBE_CONTEXT:-le-uat}"
DB_USER="${PAPER_REPORT_DB_USER:-life}"
JIRA="${JIRA_CLI:-${HOME}/Workspace/active/.claude/bin/jira.py}"
ISSUE="${PAPER_REPORT_ISSUE:-KAN-1217}"
NOW="${PAPER_REPORT_NOW:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
DRY=0
case "${1:-}" in
  --dry-run) DRY=1 ;;
  "") ;;
  -h|--help) sed -n '3,22p' "$0"; exit 0 ;;
  *) echo "paper-report: unknown argument: $1 (see --help)" >&2; exit 1 ;;
esac
export PATH="${HOME}/bin:${HOME}/.local/bin:/usr/local/bin:/usr/bin:/bin"

# The window ends 2026-11-17 at the latest; after a week of grace the cron stops commenting (remove the
# crontab line with the verdict — this only keeps it from commenting forever if that is forgotten).
if (( ! DRY )) && [[ "${NOW:0:10}" > "2026-11-24" ]]; then
  echo "paper-report: KAN-1217 window is over (> 2026-11-24), not commenting"; exit 0
fi

q() {
  if [[ -n "${PAPER_REPORT_FIXTURES:-}" ]]; then cat "${PAPER_REPORT_FIXTURES}/$1.psv"; return; fi
  kubectl --context "$CTX" -n uat exec postgres-0 -- psql -U "$DB_USER" -d life_engine_cryptobot -AtF '|' -c "$2"
}

PORTFOLIOS="$(q portfolios "select id, strategy, params, initial_usd, ticks, trades, costs_usd, started_at, last_bar_time from paper_portfolio order by id")"
TICKS="$(q ticks "select portfolio_id, bar_time, equity_usd from paper_tick order by portfolio_id, bar_time")"
GAP="$(q gap "select count(*), count(deviation_bps), round(avg(abs(deviation_bps))::numeric, 2), max(abs(deviation_bps)) from paper_price_check")"

REPORT="$(PORTFOLIOS="$PORTFOLIOS" TICKS="$TICKS" GAP="$GAP" NOW="$NOW" python3 - <<'PY'
import os
from collections import defaultdict, OrderedDict
from datetime import datetime, timedelta, timezone
from decimal import Decimal

def ts(s):
    """psql prints timestamptz as '2026-10-20 16:00:00+00'; ISO-8601 with a Z is accepted too."""
    s = s.strip().replace("Z", "+00:00")
    if "T" not in s:
        s = s.replace(" ", "T", 1)
    if s.endswith("+00"):
        s += ":00"
    return datetime.fromisoformat(s)

ports = OrderedDict()
for line in os.environ["PORTFOLIOS"].splitlines():
    if not line.strip():
        continue
    pid, strat, params, init, ticks, trades, costs, started, last = line.split("|")
    ports[pid] = dict(strategy=strat, params=params, initial=Decimal(init), ticks=int(ticks), trades=int(trades),
                      costs=Decimal(costs), started=ts(started), last=ts(last))
series = defaultdict(list)
for line in os.environ["TICKS"].splitlines():
    if not line.strip():
        continue
    pid, bar, eq = line.split("|")
    series[pid].append((ts(bar), Decimal(eq)))

now = ts(os.environ["NOW"]).astimezone(timezone.utc)
if not ports:
    print(f"REPORTE PAPER TRADING (KAN-1217) — {now:%Y-%m-%d %H:%M}Z · sin portfolios: el job todavía no aplicó ninguna vela.")
    raise SystemExit(0)

measured = []
for pid, p in ports.items():
    s = series[pid]
    if not s:
        continue
    final = s[-1][1]
    pnl = final - p["initial"]
    peak, mdd = p["initial"], Decimal(0)
    for _, e in s:
        peak = max(peak, e)
        if peak > 0:
            mdd = max(mdd, (peak - e) / peak)
    daily = OrderedDict()
    for t, e in s:
        daily[t.astimezone(timezone.utc).date()] = e
    prev, worst = p["initial"], None
    for d, e in daily.items():
        r = (e / prev - 1) * 100
        if worst is None or r < worst[1]:
            worst = (d, r)
        prev = e
    p.update(final=final, pnl=pnl, ret=pnl * 100 / p["initial"], mdd=mdd * 100, worst=worst)
    measured.append(pid)

def beats(pid, bench):
    p = ports[pid]
    if p["strategy"] == bench:
        return "—"
    b = next((x for x in ports.values() if x["strategy"] == bench and "final" in x
              and x["started"] == p["started"] and x["last"] == p["last"]), None)
    if b is None:
        return "—"
    return "sí" if p["final"] > b["final"] else "no"

first = next(iter(ports.values()))
# bar_time is when the hourly candle OPENS; it closes an hour later, and only then can the job apply it
age_min = int((now - (first["last"] + timedelta(hours=1))).total_seconds() // 60)
stale = " ⚠ el job NO avanza" if age_min > 120 else ""
lines = [
    f"REPORTE PAPER TRADING (KAN-1217) — {now:%Y-%m-%d %H:%M}Z · ventana desde {first['started']:%Y-%m-%d %H:%M}Z, "
    f"última vela {first['last']:%Y-%m-%d %H:%M}Z (día {(first['last'] - first['started']).days} de 14–28) · "
    f"cerró hace {age_min} min{stale}",
    "Fuente: tablas paper_* de life_engine_cryptobot en k8s-uat (solo lectura). 1000 USD c/u, 30+20 bps de costos.",
    "",
    "portfolio | equity USD | P&L USD | retorno | max DD | peor día (UTC) | trades | costos | > hold-sol | > hold-usdc",
]
for pid in measured:
    p = ports[pid]
    wd, wr = p["worst"]
    lines.append(f"{pid} | {p['final']:.2f} | {p['pnl']:+.2f} | {p['ret']:+.2f}% | {p['mdd']:.2f}% | {wr:+.2f}% ({wd}) | "
                 f"{p['trades']} | {p['costs']:.2f} | {beats(pid, 'hold-sol')} | {beats(pid, 'hold-usdc')}")
n, both, mean, mx = (os.environ["GAP"].strip().split("|") + ["", "", "", ""])[:4]
lines += [
    "",
    f"Coinbase vs Jupiter: {n or 0} chequeos, {both or 0} con ambos precios, desvío medio |{mean or '—'}| bps, máximo |{mx or '—'}| bps.",
    "Criterio de la ventana: una estrategia aprueba si le gana a hold-usdc Y a hold-sol, neto de costos, al cierre "
    "(mín. 2026-11-03). Un reporte intermedio no es veredicto.",
]
print("\n".join(lines))
PY
)"

if (( DRY )); then
  printf '%s\n' "$REPORT"
else
  python3 "$JIRA" comment "$ISSUE" "$REPORT"
fi
