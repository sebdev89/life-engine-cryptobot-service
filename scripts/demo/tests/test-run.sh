#!/usr/bin/env bash
# offline checks of scripts/demo/run.sh, e2e-devnet.sh, pov-v1.sh and pov-e2e.sh: argument validation, --dry-run
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

echo "pov-v1.sh — arguments and dry run"
check "--help exits 0" 0 "${DEMO}/pov-v1.sh" --help
check "unknown argument" 1 "${DEMO}/pov-v1.sh" --bogus
check "missing env file" 1 "${DEMO}/pov-v1.sh" --env-file "${TMP}/missing.env" --commit abcdef1
check "dry run exits 0" 0 "${DEMO}/pov-v1.sh" --dry-run --env-file "${TMP}/demo.env" --commit abcdef1
for want in 'value-events?anchor=true' 'http://127.0.0.1:18091' '"commitSha":"abcdef1"' '"ACCEPTED":true' 'dev-agent-17' \
            'knowledge-assets' 'production-acceptance-model@1' 'strategy-knowledge@3' '"providerId":"compute-node-8"' 'review-agent-3' \
            'units/ledger?groupBy=identity' 'pov-dev-agent-17.json' '/distribute?anchor=true' 'sebas dev-agent-17'; do
  printf '%s' "$OUT" | grep -q -F -- "$want" && ok "pov plan mentions '${want}'" || bad "pov plan lacks '${want}'"
done
check "pov dry run --no-distribute" 0 "${DEMO}/pov-v1.sh" --dry-run --no-distribute --env-file "${TMP}/demo.env" --commit abcdef1
printf '%s' "$OUT" | grep -q -F 'distribute skipped (--no-distribute)' && ok "pov plan shows the reward skipped" || bad "pov plan does not show the reward skipped"
check "pov dry run plans the revenue step" 0 "${DEMO}/pov-v1.sh" --dry-run --env-file "${TMP}/demo.env" --commit abcdef1
for want in '/revenue-events?anchor=true' 'simulated=true' '/treasury/cryptobot-001' 'SIMULATED pov-v1:abcdef1'; do
  printf '%s' "$OUT" | grep -q -F -- "$want" && ok "pov plan mentions '${want}'" || bad "pov plan lacks '${want}'"
done
check "pov dry run --proposal" 0 "${DEMO}/pov-v1.sh" --dry-run --env-file "${TMP}/demo.env" --commit abcdef1 --proposal 11111111-2222-3333-4444-555555555555
printf '%s' "$OUT" | grep -q -F 'PROPOSAL 11111111-2222-3333-4444-555555555555' && ok "pov plan uses --proposal" || bad "pov plan ignores --proposal"
check "pov dry run --no-revenue" 0 "${DEMO}/pov-v1.sh" --dry-run --no-revenue --env-file "${TMP}/demo.env" --commit abcdef1
printf '%s' "$OUT" | grep -q -F 'revenue skipped (--no-revenue)' && ok "pov plan shows the revenue skipped" || bad "pov plan does not show the revenue skipped"
check "pov dry run with a fixed --accepted-at" 0 "${DEMO}/pov-v1.sh" --dry-run --env-file "${TMP}/demo.env" --commit abcdef1 --accepted-at 2026-09-30T10:00:00Z
printf '%s' "$OUT" | grep -q -F '"acceptedAt":"2026-09-30T10:00:00Z"' && ok "pov uses --accepted-at" || bad "pov ignores --accepted-at"
check "pov bad --accepted-at" 1 "${DEMO}/pov-v1.sh" --dry-run --env-file "${TMP}/demo.env" --commit abcdef1 --accepted-at yesterday

echo "pov-e2e.sh — arguments and dry run (V9)"
check "--help exits 0" 0 "${DEMO}/pov-e2e.sh" --help
for want in 'STEP 1/9' 'STEP 9/9' '--acceptance-json' '--assume-accepted' '--skip-op' 'functional_acceptance → ACCEPTED'; do
  printf '%s' "$OUT" | grep -q -F -- "$want" && ok "e2e help mentions '${want}'" || bad "e2e help lacks '${want}'"
done
printf '%s' "$OUT" | grep -q 'KAN-' && bad "e2e help shows a tracker id" || ok "e2e help shows no tracker id"
check "unknown argument" 1 "${DEMO}/pov-e2e.sh" --bogus
check "no acceptance is refused" 1 "${DEMO}/pov-e2e.sh" --dry-run --env-file "${TMP}/demo.env"
printf '%s' "$OUT" | grep -q -F -- '--acceptance-json' && ok "says how to pass acceptance" || bad "does not say how to pass acceptance"
check "both acceptance modes are refused" 1 "${DEMO}/pov-e2e.sh" --dry-run --env-file "${TMP}/demo.env" --assume-accepted --acceptance-json "${TMP}/rt.json"
check "a KAN task id is refused" 1 "${DEMO}/pov-e2e.sh" --dry-run --env-file "${TMP}/demo.env" --assume-accepted --task-id KAN-819
check "a KAN id in the title is refused" 1 "${DEMO}/pov-e2e.sh" --dry-run --env-file "${TMP}/demo.env" --assume-accepted --task "fix KAN-819"
check "bad --revenue-lamports" 1 "${DEMO}/pov-e2e.sh" --dry-run --env-file "${TMP}/demo.env" --assume-accepted --revenue-lamports 0.05
check "bad --image-digest" 1 "${DEMO}/pov-e2e.sh" --dry-run --env-file "${TMP}/demo.env" --assume-accepted --image-digest sha256:xyz
check "missing --acceptance-json file" 1 "${DEMO}/pov-e2e.sh" --dry-run --env-file "${TMP}/demo.env" --acceptance-json "${TMP}/missing.json"
check "e2e dry run (manual acceptance, skip op)" 0 "${DEMO}/pov-e2e.sh" --dry-run --env-file "${TMP}/demo.env" --assume-accepted --skip-op \
  --task-id TASK-042 --commit abcdef1 --pr https://example.test/pr/1 --image-digest "sha256:$(printf '0%.0s' {1..64})"
for want in 'http://127.0.0.1:18091' '(TASK-042)' 'acceptance asserted manually' 'source=manual' 'value-events?anchor=true' \
            '/distribute?anchor=true' 'skipped (--skip-op)' 'SIMULATED pov-e2e:TASK-042' '/revenue-events?anchor=true' '/treasury/cryptobot-001' \
            'units/ledger?groupBy=identity' 'COMPUTE_PROVIDER' 'strategy-knowledge@3' '(estimated)'; do
  printf '%s' "$OUT" | grep -q -F -- "$want" && ok "e2e plan mentions '${want}'" || bad "e2e plan lacks '${want}'"
done
[[ -z "$(ls "${TMP}"/out/pov-e2e-*.md 2>/dev/null)" ]] && ok "e2e dry run writes no report" || bad "e2e dry run wrote a report"
# release-truth → the five stages: every mapped field PASS ⇒ true; one FAIL ⇒ that stage false; k8s type ⇒ <env>-k8s.
rt() { # rt <effective_config_hash verdict>
  printf '{"environment":"uat","workload":"cryptobot","type":"k8s-pod","fields":{"commit_sha":{"verdict":"PASS","note":"pod commit=abc"},"repository":{"verdict":"PASS"},"build_id":{"verdict":"N/A"},"image_digest":{"verdict":"PASS"},"deployment_revision":{"verdict":"PASS"},"effective_config_hash":{"verdict":"%s"},"health":{"verdict":"PASS"},"functional_acceptance":{"verdict":"PASS"}},"evidence":{"observed_at":"2026-09-30T10:00:00Z"}}' "$1"
}
rt PASS > "${TMP}/rt.json"
check "e2e dry run with release-truth (all PASS)" 0 "${DEMO}/pov-e2e.sh" --dry-run --env-file "${TMP}/demo.env" --acceptance-json "${TMP}/rt.json" --commit abcdef1
for want in 'source=release-truth' 'environment=uat-k8s' "'DEPLOYED': True" "'ACCEPTED': True" 'PROPOSAL <step 6>' 'e2e-devnet.sh --base-url http://127.0.0.1:18091'; do
  printf '%s' "$OUT" | grep -q -F -- "$want" && ok "e2e plan mentions '${want}'" || bad "e2e plan lacks '${want}'"
done
printf '%s' "$OUT" | grep -q 'asserted manually' && bad "release-truth run says manual" || ok "release-truth run is not manual"
rt FAIL > "${TMP}/rt-fail.json"
check "e2e dry run with release-truth (config FAIL)" 0 "${DEMO}/pov-e2e.sh" --dry-run --env-file "${TMP}/demo.env" --acceptance-json "${TMP}/rt-fail.json" --commit abcdef1
printf '%s' "$OUT" | grep -q -F "'DEPLOYED': False" && ok "a FAIL verdict makes DEPLOYED false" || bad "a FAIL verdict did not make DEPLOYED false"
printf '%s' "$OUT" | grep -q -F "'RUNNING': True" && ok "the other stages stay true" || bad "the other stages changed"
D1="sha256:$(printf '1%.0s' {1..64})"; D2="sha256:$(printf '2%.0s' {1..64})"
sed "s|\"image_digest\":{\"verdict\":\"PASS\"}|\"image_digest\":{\"verdict\":\"PASS\",\"note\":\"pod image: ghcr.io/x/y@${D1} (argo)\"}|" "${TMP}/rt.json" > "${TMP}/rt-img.json"
check "e2e dry run takes the image release-truth measured" 0 "${DEMO}/pov-e2e.sh" --dry-run --env-file "${TMP}/demo.env" --acceptance-json "${TMP}/rt-img.json" --commit abcdef1
printf '%s' "$OUT" | grep -q -F "image ${D1}" && ok "artifact image = measured image" || bad "artifact image is not the measured one"
check "another --image-digest than the measured one is refused" 1 "${DEMO}/pov-e2e.sh" --dry-run --env-file "${TMP}/demo.env" --acceptance-json "${TMP}/rt-img.json" --commit abcdef1 --image-digest "$D2"
printf '%s' "$OUT" | grep -q 'nobody measured' && ok "says the acceptance was not measured for that image" || bad "does not explain the refusal"
echo '{"model":"claude-opus","inputTokens":1000,"outputTokens":200,"gpuSeconds":1.5,"estimatedCostMicroUsd":42}' > "${TMP}/compute.json"
check "e2e dry run with --compute-json" 0 "${DEMO}/pov-e2e.sh" --dry-run --env-file "${TMP}/demo.env" --assume-accepted --compute-json "${TMP}/compute.json"
printf '%s' "$OUT" | grep -q -F '"inputTokens":1000' && ok "measured compute is used" || bad "measured compute is ignored"
printf '%s' "$OUT" | grep -q -F '(estimated)' && bad "measured compute is labelled estimated" || ok "measured compute is not labelled estimated"

echo "secrets_scan (lib.sh) — only secret-named values, never public ones"
cat > "${TMP}/secrets.env" <<'EOF'
JWT_SECRET=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
CRYPTOBOT_DB_USER=cryptobot
CRYPTOBOT_DB_PASSWORD=pppppppppppppppppppppppp
DEMO_WALLET_ADDRESS=G4bCRqj3yjQZyMrjxETKvrEhXeYhNzsGY97kipXr4exS
VALIDATOR_POLICY_HASH=sha256:0000000000000000000000000000000000000000000000000000000000000000
EOF
printf 'user cryptobot · wallet G4bCRqj3yjQZyMrjxETKvrEhXeYhNzsGY97kipXr4exS · sha256:0000000000000000000000000000000000000000000000000000000000000000\n' > "${TMP}/clean.md"
check "public values and the DB user are not leaks" 0 bash -c "source '${DEMO}/lib.sh'; secrets_scan '${TMP}/secrets.env' '${TMP}/clean.md'"
printf '%s' "$OUT" | grep -q 'searched=2 hits=0' && ok "only the 2 secret values are searched" || bad "unexpected scan: ${OUT}"
printf 'oops pppppppppppppppppppppppp\n' > "${TMP}/leak.md"
check "a secret value is a leak" 1 bash -c "source '${DEMO}/lib.sh'; secrets_scan '${TMP}/secrets.env' '${TMP}/clean.md' '${TMP}/leak.md'"
printf '%s' "$OUT" | grep -q 'LEAK CRYPTOBOT_DB_PASSWORD' && ok "names the leaked key" || bad "does not name the leaked key"
printf '%s' "$OUT" | grep -q -F 'pppppppppppppppppppppppp' && bad "prints the secret value" || ok "never prints the value"

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
  shellcheck -S warning "${DEMO}"/run.sh "${DEMO}"/e2e-devnet.sh "${DEMO}"/wallet-devnet.sh "${DEMO}"/pov-v1.sh "${DEMO}"/pov-e2e.sh "${DEMO}"/lib.sh "${HERE}"/test-run.sh && ok "shellcheck -S warning" || bad "shellcheck"
else
  echo "  (shellcheck not installed: skipped)"
fi

echo
echo "test-run.sh: ${pass} passed, ${failn} failed"
(( failn == 0 ))
