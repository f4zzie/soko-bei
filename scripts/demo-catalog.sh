#!/usr/bin/env bash
set -euo pipefail
command -v docker >/dev/null || { echo 'Missing required tool: docker' >&2; exit 1; }
exec docker exec "${BACKEND_CONTAINER:-soko-backend}" /app/bin/backend demo-catalog "$@"
