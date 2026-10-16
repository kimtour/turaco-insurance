#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/setup-local.py
# All compute stays in this Codespace. Only the dashboard port is forwarded.
if [[ "${1:-}" == "--build" ]]; then
  docker compose -f compose.yaml -f .devcontainer/compose.cloud.yaml up -d --build
else
  docker compose -f compose.yaml -f .devcontainer/compose.cloud.yaml up -d
fi
python3 scripts/cloud-demo-check.py --url http://127.0.0.1:5173 --seed
