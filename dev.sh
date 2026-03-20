#!/usr/bin/env bash
set -euo pipefail

PORT=3030

echo "Stopping existing compose stack..."
docker compose down --remove-orphans >/dev/null 2>&1 || true

if PIDS="$(lsof -ti tcp:${PORT} 2>/dev/null)" && [[ -n "${PIDS}" ]]; then
  echo "Killing process(es) on port ${PORT}: ${PIDS}"
  kill ${PIDS} 2>/dev/null || true
  sleep 1
fi

echo "Starting docker compose on http://localhost:${PORT}"
echo "Starting with live rebuilds on Go source changes (compose watch)..."
exec docker compose up --build --watch --remove-orphans
