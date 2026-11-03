#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [[ -f .env ]]; then
    set -a
    # .env is a local, trusted shell environment file. It is never shipped to clients.
    source .env
    set +a
fi
exec ./gradlew :server:run "$@"
