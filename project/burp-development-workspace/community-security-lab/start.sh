#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
docker compose up -d --build --wait --wait-timeout 900
printf 'Open http://127.0.0.1:8088
'
