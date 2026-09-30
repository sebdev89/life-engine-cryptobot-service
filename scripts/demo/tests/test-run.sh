#!/usr/bin/env bash
# KAN-575 — offline checks of scripts/demo/run.sh and e2e-devnet.sh: argument validation, --dry-run
# plan, help text, gitignore of the secret files. No docker call is made (the dry run stops before
# `compose up`); docker, curl, python3 and git must exist because the plan checks the tools.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO="$(cd "${HERE}/.." && pwd)"
PROJECT="$(cd "${DEMO}/../.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
pass=0; failn=0
ok()   { pass=$((pass + 1)); printf '  ✓ %s\n' "$*"; }
bad()  { failn=$((failn + 1)); printf '  ✗ %s\n' "$*"; }
check() { # check "<name>" <expected-rc> <cmd…>   (stdout+stderr captured in $OUT)
  local name="$1" want="$2"; shift 2
  OUT="$("$@" 2>&1)"; local rc=$?
  if [[ "$rc" -eq "$want" ]]; then ok "${name} (rc ${rc})"; else bad "${name}: rc ${rc}, expected ${want}: $(printf '%s' "$OUT" | tail -3)"; fi
}

echo "run.sh — arguments"
check "--help exits 0" 0 "${DEMO}/run.sh" --help
printf '%s' "$OUT" | grep -q -- '--target local|uat' && ok "help names --target" || bad "help lacks --target"
check "unknown argument" 1 "${DEMO}/run.sh" --bogus
check "bad --target" 1 "${DEMO}/run.sh" --target prod
check "bad --rpc" 1 "${DEMO}/run.sh" --rpc mainnet
check "--target uat --rpc local is refused" 1 "${DEMO}/run.sh" --target uat --rpc local
check "--target uat without env file" 1 "${DEMO}/run.sh" --target uat --dry-run --env-file "${TMP}/missing.env" --out "${TMP}/out"
printf '%s' "$OUT" | grep -q 'no .*missing.env' && ok "says which env file is missing" || bad "does not name the missing env file"

echo "run.sh — dry run (local target, local RPC, temp env file)"
cat > "${TMP}/demo.env" <<'EOF'
DEMO_WALLET_ADDRESS=G4bCRqj3yjQZyMrjxETKvrEhXeYhNzsGY97kipXr4exS
CRYPTOBOT_REBALANCE_VAULT=FrBNyfCUpmJXFCgdZyJyKLiqHzb9xRAVUiqiXtGm6BmX
CRYPTOBOT_DEMO_PORT=18091
EOF
check "dry run exits 0" 0 "${DEMO}/run.sh" --dry-run --rpc local --env-file "${TMP}/demo.env" --out "${TMP}/out"
for want in 'act 1 execute' 'act 2 risk' 'ASSET_ALLOWLIST' 'tampered at rest' 'PRICE_DEVIATION' 'act 3 recovery' 'act 4 evidence' '--local-validator' 'http://127.0.0.1:18091' 'dry run'; do
  printf '%s' "$OUT" | grep -q -F -- "$want" && ok "plan mentions '${want}'" || bad "plan lacks '${want}'"
done
[[ -z "$(ls "${TMP}/out"/demo-report-*.md 2>/dev/null)" ]] && ok "dry run writes no report" || bad "dry run wrote a report"
check "dry run with --no-recovery --no-anchor" 0 "${DEMO}/run.sh" --dry-run --rpc local --no-recovery --no-anchor --env-file "${TMP}/demo.env" --out "${TMP}/out"
printf '%s' "$OUT" | grep -q 'skipped (--no-recovery)' && ok "plan shows recovery skipped" || bad "plan does not show recovery skipped"

echo "run.sh — dry run (uat target)"
cat > "${TMP}/uat.env" <<'EOF'
CRYPTOBOT_DEMO_BASE_URL=https://cryptobot-uat.example.test/
DEMO_WALLET_ADDRESS=G4bCRqj3yjQZyMrjxETKvrEhXeYhNzsGY97kipXr4exS
CRYPTOBOT_REBALANCE_VAULT=FrBNyfCUpmJXFCgdZyJyKLiqHzb9xRAVUiqiXtGm6BmX
EOF
check "uat dry run exits 0" 0 "${DEMO}/run.sh" --target uat --dry-run --env-file "${TMP}/uat.env" --out "${TMP}/out"
printf '%s' "$OUT" | grep -q -F -- '--base-url https://cryptobot-uat.example.test ' && ok "plan targets the base URL (trailing slash stripped)" || bad "plan does not target the base URL"
printf '%s' "$OUT" | grep -q -F -- '--no-up' && bad "uat plan must not use --no-up" || ok "uat plan does not use the compose"

echo "e2e-devnet.sh — arguments"
check "--help exits 0" 0 "${DEMO}/e2e-devnet.sh" --help
check "bad --chaos" 1 "${DEMO}/e2e-devnet.sh" --chaos explode
check "--it with --base-url is refused" 1 "${DEMO}/e2e-devnet.sh" --it --base-url http://x
check "missing env file" 1 "${DEMO}/e2e-devnet.sh" --env-file "${TMP}/missing.env"

echo "pov-v1.sh — arguments and dry run (KAN-818)"
check "--help exits 0" 0 "${DEMO}/pov-v1.sh" --help
check "unknown argument" 1 "${DEMO}/pov-v1.sh" --bogus
check "missing env file" 1 "${DEMO}/pov-v1.sh" --env-file "${TMP}/missing.env" --commit abcdef1
check "dry run exits 0" 0 "${DEMO}/pov-v1.sh" --dry-run --env-file "${TMP}/demo.env" --commit abcdef1
for want in 'value-events?anchor=true' 'http://127.0.0.1:18091' '"commitSha":"abcdef1"' '"ACCEPTED":true' 'dev-agent-17' \
            'knowledge-assets' 'production-acceptance-model@1' 'strategy-knowledge@3' '"providerId":"compute-node-8"' 'review-agent-3' \
            'units/ledger?groupBy=identity' 'pov-dev-agent-17.json'; do
  printf '%s' "$OUT" | grep -q -F -- "$want" && ok "pov plan mentions '${want}'" || bad "pov plan lacks '${want}'"
done
check "pov dry run with a fixed --accepted-at" 0 "${DEMO}/pov-v1.sh" --dry-run --env-file "${TMP}/demo.env" --commit abcdef1 --accepted-at 2026-09-30T10:00:00Z
printf '%s' "$OUT" | grep -q -F '"acceptedAt":"2026-09-30T10:00:00Z"' && ok "pov uses --accepted-at" || bad "pov ignores --accepted-at"
check "pov bad --accepted-at" 1 "${DEMO}/pov-v1.sh" --dry-run --env-file "${TMP}/demo.env" --commit abcdef1 --accepted-at yesterday

echo "secrets stay out of git"
if git -C "$PROJECT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  # `out/` (directory rule) only matches an existing directory by its bare name: ask for a path inside it.
  for f in .env.demo .env.demo-uat out/demo-report-x.md; do
    git -C "$PROJECT" check-ignore -q "$f" && ok "${f} is gitignored" || bad "${f} is NOT gitignored"
  done
  git -C "$PROJECT" ls-files --error-unmatch .env.demo-uat.example >/dev/null 2>&1 && ok ".env.demo-uat.example is tracked" || bad ".env.demo-uat.example is not tracked"
else
  echo "  (not a git checkout: skipped)"
fi

echo "lint"
if command -v shellcheck >/dev/null 2>&1; then
  shellcheck -S warning "${DEMO}"/run.sh "${DEMO}"/e2e-devnet.sh "${DEMO}"/wallet-devnet.sh "${DEMO}"/pov-v1.sh "${HERE}"/test-run.sh && ok "shellcheck -S warning" || bad "shellcheck"
else
  echo "  (shellcheck not installed: skipped)"
fi

echo
echo "test-run.sh: ${pass} passed, ${failn} failed"
(( failn == 0 ))
