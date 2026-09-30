#!/usr/bin/env bash
# KAN-794 — the URL that opens the demo UI already signed in.
#
# The demo stack has no Life Engine Auth: the service verifies HS256 tokens with the JWT_SECRET of
# .env.demo (see jwt_hs256 in lib.sh). This mints one (RUNTIME_OPERATOR + RUNTIME_ADMIN, valid 1 h)
# and prints   http://127.0.0.1:${UI_PORT:-4204}/live?token=<jwt>
# The UI consumes ?token= once and keeps it in localStorage (cryptobot-ui src/app/session.ts).
#
#   scripts/demo/ui-url.sh                 # uses ./.env.demo
#   scripts/demo/ui-url.sh --env-file <f>  # another env file
#   scripts/demo/ui-url.sh --path /        # another route (default /live)
#
# The token is a local, 1 h, demo-only credential; the JWT_SECRET itself is never printed.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "${HERE}/../.." && pwd)"
# shellcheck source=lib.sh
source "${HERE}/lib.sh"

ENV_FILE="${PROJECT}/.env.demo"; ROUTE=/live
while (( $# )); do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --path) ROUTE="$2"; shift 2 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[[ -f "$ENV_FILE" ]] || fail "no ${ENV_FILE}: run scripts/demo/wallet-devnet.sh (or run.sh) first"

secret="$(sed -n 's/^JWT_SECRET=//p' "$ENV_FILE" | tail -1)"
[[ -n "$secret" ]] || fail "JWT_SECRET missing in ${ENV_FILE}"
port="$(sed -n 's/^UI_PORT=//p' "$ENV_FILE" | tail -1)"; port="${UI_PORT:-${port:-4204}}"
# A stable subject: every URL printed is the same demo operator.
sub="$(python3 -c 'import uuid; print(uuid.uuid5(uuid.NAMESPACE_DNS, "demo@cryptobot.local"))')"
token="$(jwt_hs256 "$secret" "$sub" "demo@cryptobot.local")"
printf 'http://127.0.0.1:%s%s?token=%s\n' "$port" "$ROUTE" "$token"
