#!/usr/bin/env bash
# offline checks of scripts/pov/pov-collector.py (KAN-1222): fixtures instead of release-truth/git/gh, a local fake API.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 -m unittest discover -s "$HERE" -v
