#!/usr/bin/env bash
# Exercise the real API and Flyway migrations against an isolated, disposable DB.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

command -v docker >/dev/null || { echo "Docker is required for PostgreSQL integration tests." >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Start Docker before running this script." >&2; exit 1; }

test_container="bohoman-tests-$(date +%s)-$$"
test_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
cleanup() { docker rm -fv "$test_container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

docker run --detach --name "$test_container" \
    --publish 127.0.0.1::5432 \
    --env POSTGRES_DB=bohoman_test \
    --env POSTGRES_USER=bohoman_test \
    --env POSTGRES_PASSWORD="$test_password" \
    "${BOHOMAN_TEST_POSTGRES_IMAGE:-postgres:18}" >/dev/null

ready=false
for ((attempt = 0; attempt < 60; attempt++)); do
    if docker exec "$test_container" pg_isready -U bohoman_test -d bohoman_test >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
if [[ "$ready" != true ]]; then
    docker logs "$test_container" >&2
    echo "Test PostgreSQL did not become ready." >&2
    exit 1
fi

test_port="$(docker port "$test_container" 5432/tcp | awk -F: '{print $NF}')"
export BOHOMAN_TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:${test_port}/bohoman_test"
export BOHOMAN_TEST_DATABASE_USER=bohoman_test
export BOHOMAN_TEST_DATABASE_PASSWORD="$test_password"
echo "Running server tests with disposable PostgreSQL on port ${test_port}."
# Always rerun: a cached/skipped test result is not a database acceptance run.
./gradlew :server:test --rerun-tasks --console=plain "$@"
