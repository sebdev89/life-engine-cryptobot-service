#!/usr/bin/env bash
# Boot cryptobot-service against a locally running life-engine-runtime + auth.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "${HERE}/.." && pwd)"

if [[ -f "${PROJECT}/.env.local" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${PROJECT}/.env.local"
  set +a
fi

if [[ -z "${JWT_SECRET:-}" || ${#JWT_SECRET} -lt 32 ]]; then
  echo "JWT_SECRET must be set and at least 32 characters (matches life-engine-auth + runtime)." >&2
  exit 1
fi

cd "${PROJECT}"
exec mvn spring-boot:run
