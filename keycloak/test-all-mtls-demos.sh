#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEMOS=("spiffe-postgres-mtls" "spiffe-mysql-mtls" "spiffe-mariadb-mtls" "spiffe-mssql-mtls" "spiffe-oracle-mtls")
KEYCLOAK_URL="http://localhost:8080"
KEYCLOAK_MGMT_URL="http://localhost:9000"
TOKEN_ENDPOINT="${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token"
MAX_WAIT=300  # seconds to wait for Keycloak to become ready
PASSED=()

log() { echo "=== $(date '+%H:%M:%S') $*"; }

wait_for_keycloak() {
    local elapsed=0
    log "Waiting up to ${MAX_WAIT}s for Keycloak to be ready..."
    while [ $elapsed -lt $MAX_WAIT ]; do
        if curl -sf "${KEYCLOAK_MGMT_URL}/health/ready" > /dev/null 2>&1; then
            log "Keycloak health endpoint is ready"
            return 0
        fi
        sleep 3
        elapsed=$((elapsed + 3))
    done
    log "ERROR: Keycloak did not become ready within ${MAX_WAIT}s"
    return 1
}

try_login() {
    local response http_code body
    response=$(curl -s -w "\n%{http_code}" \
        -d "grant_type=password" \
        -d "client_id=admin-cli" \
        -d "username=admin" \
        -d "password=admin" \
        "${TOKEN_ENDPOINT}")

    http_code=$(echo "$response" | tail -1)
    body=$(echo "$response" | sed '$d')

    if [ "$http_code" = "200" ] && echo "$body" | grep -q "access_token"; then
        return 0
    fi
    log "Login failed (HTTP ${http_code})"
    return 1
}

run_demo() {
    local demo=$1
    local demo_dir="${SCRIPT_DIR}/${demo}"

    log "--- Starting demo: ${demo} ---"
    cd "$demo_dir"
    docker compose down -v
    docker compose up --build -d 2>&1

    if ! wait_for_keycloak; then
        log "FAIL: ${demo} - Keycloak never became ready"
        log "Leaving resources running for debugging"
        docker compose logs keycloak 2>/dev/null | tail -20
        exit 1
    fi

    # Give Keycloak a moment after health check passes
    sleep 2

    if ! try_login; then
        log "FAIL: ${demo} - Admin login failed"
        log "Leaving resources running for debugging"
        docker compose logs keycloak 2>/dev/null | tail -20
        exit 1
    fi

    log "PASS: ${demo} - Keycloak login successful"
    PASSED+=("$demo")

    log "Tearing down ${demo}..."
    docker compose down -v 2>&1
    log ""
}

log "Testing ${#DEMOS[@]} demos: ${DEMOS[*]}"
log ""

for demo in "${DEMOS[@]}"; do
    run_demo "$demo"
done

log "All ${#DEMOS[@]} demos passed:"
for demo in "${PASSED[@]}"; do
    log "  PASS: ${demo}"
done
