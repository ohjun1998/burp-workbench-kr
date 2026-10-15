#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p verification-results
collect() {
  docker compose ps --all --format json > verification-results/container-status.json 2>&1 || true
  docker compose images --format json > verification-results/container-images.json 2>&1 || true
  docker compose logs --no-color --tail 300 db was web protocols > verification-results/containers.log 2>&1 || true
}
trap collect EXIT
docker info
docker compose version
docker compose -f compose.yaml -f compose.verify.yaml config --quiet
docker compose up -d --build --wait --wait-timeout 900
if command -v curl >/dev/null 2>&1; then
  curl --fail --silent --show-error --retry 10 --retry-connrefused --retry-delay 1 --retry-max-time 30 --max-time 5 http://127.0.0.1:8088/health > verification-results/host-health.json
fi
docker compose -f compose.yaml -f compose.verify.yaml run --rm verify
printf 'Verification passed. Open http://127.0.0.1:8088\n'
