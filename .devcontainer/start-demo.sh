#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/setup-local.py
compose=(docker compose -f compose.yaml -f .devcontainer/compose.cloud.yaml)
images=(turaco-insurance-insurance-api:latest turaco-insurance-ai-service:latest turaco-insurance-provider-simulator:latest turaco-insurance-claims-dashboard:latest)
if [[ "${1:-}" == "--build" ]] || ! docker image inspect "${images[@]}" >/dev/null 2>&1; then
  mkdir -p .cache
  "${compose[@]}" build --print > .cache/cloud-bake.json
  docker buildx bake --file .cache/cloud-bake.json --allow=network.host --load
fi
"${compose[@]}" up -d --no-build
python3 scripts/cloud-demo-check.py --url http://127.0.0.1:5173 --seed
