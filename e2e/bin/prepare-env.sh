#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_DIR="${ROOT_DIR}/.e2e-tmp"
LOG_DIR="${TMP_DIR}/logs"
PID_DIR="${TMP_DIR}/pids"

TECHNICAL_CREDENTIALS_KEY="${TECHNICAL_CREDENTIALS_KEY:-dev-only-technical-credentials-key}"
PROXY_TLS_CERT_FILE="${PROXY_TLS_CERT_FILE:-${ROOT_DIR}/certs/proxy.crt}"
PROXY_TLS_KEY_FILE="${PROXY_TLS_KEY_FILE:-${ROOT_DIR}/certs/proxy.key}"
APP_URL="${APP_URL:-http://localhost:5173}"
SERVER_HEALTH_URL="${SERVER_HEALTH_URL:-http://localhost:8080/api/v1/health}"
KEYCLOAK_REALM_URL="${KEYCLOAK_REALM_URL:-http://localhost:8180/realms/db-watchdog}"
TEST_DATABASE_NAME="${TEST_DATABASE_NAME:-playwright_ui_proxy_e2e}"

mkdir -p "${LOG_DIR}" "${PID_DIR}"
bash "${ROOT_DIR}/e2e/bin/cleanup-env.sh" >/dev/null 2>&1 || true

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

wait_for_http() {
  local url="$1"
  local label="$2"
  local attempts="${3:-60}"

  for ((attempt = 1; attempt <= attempts; attempt += 1)); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi

    sleep 2
  done

  echo "Timed out waiting for ${label} at ${url}" >&2
  exit 1
}

wait_for_tcp() {
  local host="$1"
  local port="$2"
  local label="$3"
  local attempts="${4:-60}"

  for ((attempt = 1; attempt <= attempts; attempt += 1)); do
    if (exec 3<>"/dev/tcp/${host}/${port}") >/dev/null 2>&1; then
      return 0
    fi

    sleep 2
  done

  echo "Timed out waiting for ${label} on ${host}:${port}" >&2
  exit 1
}

start_if_needed() {
  local pid_file="$1"
  local wait_kind="$2"
  local target_a="$3"
  local target_b="$4"
  local label="$5"
  local log_file="$6"
  shift 6

  if [[ "$wait_kind" == "http" ]]; then
    if curl -fsS "$target_a" >/dev/null 2>&1; then
      echo "${label} already running"
      return 0
    fi
  else
    if (exec 3<>"/dev/tcp/${target_a}/${target_b}") >/dev/null 2>&1; then
      echo "${label} already running"
      return 0
    fi
  fi

  echo "Starting ${label}"
  setsid "$@" >"${log_file}" 2>&1 &
  echo "$!" >"${pid_file}"

  if [[ "$wait_kind" == "http" ]]; then
    wait_for_http "$target_a" "$label"
  else
    wait_for_tcp "$target_a" "$target_b" "$label"
  fi
}

require_command bash
require_command curl
require_command docker
require_command psql
require_command pnpm
require_command go
require_command sbt
require_command setsid

echo "Starting docker dependencies"
(
  cd "${ROOT_DIR}"
  docker compose up -d
)

wait_for_tcp "localhost" "54320" "system PostgreSQL"
wait_for_tcp "localhost" "54321" "external PostgreSQL"
wait_for_http "${KEYCLOAK_REALM_URL}" "Keycloak realm"

echo "Ensuring external test database exists"
PGPASSWORD=password psql \
  --host localhost \
  --port 54321 \
  --username postgres \
  --dbname postgres \
  --tuples-only \
  --no-align \
  --command "SELECT 1 FROM pg_database WHERE datname = '${TEST_DATABASE_NAME}'" \
  | grep -qx '1' || \
PGPASSWORD=password psql \
  --host localhost \
  --port 54321 \
  --username postgres \
  --dbname postgres \
  --command "CREATE DATABASE ${TEST_DATABASE_NAME} OWNER proxy_user_1"

start_if_needed \
  "${PID_DIR}/server.pid" \
  http \
  "${SERVER_HEALTH_URL}" \
  "" \
  "backend" \
  "${LOG_DIR}/server.log" \
  env TECHNICAL_CREDENTIALS_KEY="${TECHNICAL_CREDENTIALS_KEY}" \
  bash -lc "cd '${ROOT_DIR}/server' && sbt run"

start_if_needed \
  "${PID_DIR}/proxy.pid" \
  tcp \
  "localhost" \
  "5432" \
  "reverse proxy" \
  "${LOG_DIR}/proxy.log" \
  env \
  TECHNICAL_CREDENTIALS_KEY="${TECHNICAL_CREDENTIALS_KEY}" \
  TLS_CERT_FILE="${PROXY_TLS_CERT_FILE}" \
  TLS_KEY_FILE="${PROXY_TLS_KEY_FILE}" \
  bash -lc "cd '${ROOT_DIR}/reverse-proxy' && go run ."

start_if_needed \
  "${PID_DIR}/ui.pid" \
  http \
  "${APP_URL}" \
  "" \
  "frontend" \
  "${LOG_DIR}/ui.log" \
  bash -lc "cd '${ROOT_DIR}/ui' && pnpm dev --host 127.0.0.1 --port 5173"

echo "E2E environment is ready"
