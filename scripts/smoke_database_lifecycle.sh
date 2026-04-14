#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

KEYCLOAK_URL="http://localhost:8180"
API_URL="http://localhost:8080/api/v1"
SYSTEM_DB_PORT="54320"
EXTERNAL_DB_PORT="54321"
PROXY_PORT="5432"

SMOKE_DB_NAME="smoke_lifecycle_e2e"
REGISTERED_ENGINE="postgres"
REGISTERED_HOST="localhost"
REGISTERED_PORT="$EXTERNAL_DB_PORT"
TECHNICAL_USER="proxy_user_1"
TECHNICAL_PASSWORD="proxy_pass"

ADMIN_USERNAME="admin"
ADMIN_PASSWORD="admin123"
USER_USERNAME="user"
USER_PASSWORD="user123"
USER_EMAIL="user@example.com"
TEAM_NAME="Engineering"

SERVER_PID=""
PROXY_PID=""
ADMIN_TOKEN=""
DATABASE_ID=""

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

cleanup() {
  if [[ -n "$DATABASE_ID" && -n "$ADMIN_TOKEN" ]]; then
    curl -sS -X POST \
      -H "Authorization: Bearer $ADMIN_TOKEN" \
      "${API_URL}/admin/databases/${DATABASE_ID}/deactivate" >/dev/null || true
  fi

  if [[ -n "$PROXY_PID" ]]; then
    kill "$PROXY_PID" >/dev/null 2>&1 || true
    wait "$PROXY_PID" >/dev/null 2>&1 || true
  fi

  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" >/dev/null 2>&1 || true
    wait "$SERVER_PID" >/dev/null 2>&1 || true
  fi
}

trap cleanup EXIT

wait_for_http() {
  local url="$1"
  local label="$2"

  for _ in $(seq 1 120); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done

  echo "Timed out waiting for ${label}: ${url}" >&2
  exit 1
}

wait_for_port() {
  local host="$1"
  local port="$2"
  local label="$3"

  for _ in $(seq 1 120); do
    if (echo >"/dev/tcp/${host}/${port}") >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done

  echo "Timed out waiting for ${label} on ${host}:${port}" >&2
  exit 1
}

ensure_external_db() {
  local exists

  exists="$(
    PGPASSWORD=password psql \
      -h localhost \
      -p "$EXTERNAL_DB_PORT" \
      -U postgres \
      -d postgres \
      -Atqc "SELECT 1 FROM pg_database WHERE datname = '${SMOKE_DB_NAME}'"
  )"

  if [[ "$exists" != "1" ]]; then
    PGPASSWORD=password psql \
      -h localhost \
      -p "$EXTERNAL_DB_PORT" \
      -U postgres \
      -d postgres <<SQL
CREATE DATABASE ${SMOKE_DB_NAME};
GRANT ALL ON DATABASE ${SMOKE_DB_NAME} TO my_user;
\c ${SMOKE_DB_NAME}
GRANT ALL ON SCHEMA public TO my_user;
SQL
  fi
}

get_token() {
  local username="$1"
  local password="$2"

  curl -fsS \
    -X POST \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password" \
    -d "client_id=db-watchdog-frontend" \
    -d "username=${username}" \
    -d "password=${password}" \
    "${KEYCLOAK_URL}/realms/db-watchdog/protocol/openid-connect/token" |
    jq -r '.access_token'
}

api_json() {
  local method="$1"
  local token="$2"
  local path="$3"
  local body="${4:-}"

  if [[ -n "$body" ]]; then
    curl -fsS \
      -X "$method" \
      -H "Authorization: Bearer ${token}" \
      -H "Content-Type: application/json" \
      -d "$body" \
      "${API_URL}${path}"
  else
    curl -fsS \
      -X "$method" \
      -H "Authorization: Bearer ${token}" \
      "${API_URL}${path}"
  fi
}

api_status() {
  local method="$1"
  local token="$2"
  local path="$3"

  curl -sS \
    -o /dev/null \
    -w '%{http_code}' \
    -X "$method" \
    -H "Authorization: Bearer ${token}" \
    "${API_URL}${path}"
}

require_command curl
require_command jq
require_command psql
require_command docker
require_command go
require_command sbt

cd "$ROOT_DIR"

docker compose up -d

wait_for_port "localhost" "$SYSTEM_DB_PORT" "system database"
wait_for_port "localhost" "$EXTERNAL_DB_PORT" "external database"
wait_for_http "${KEYCLOAK_URL}/realms/db-watchdog/.well-known/openid-configuration" "Keycloak"

ensure_external_db

if ! curl -fsS "${API_URL}/health" >/dev/null 2>&1; then
  (
    cd "$ROOT_DIR/server"
    sbt run
  ) >"$ROOT_DIR/.smoke-server.log" 2>&1 &
  SERVER_PID="$!"
fi

wait_for_http "${API_URL}/health" "backend API"

if ! (echo >"/dev/tcp/localhost/${PROXY_PORT}") >/dev/null 2>&1; then
  (
    cd "$ROOT_DIR/reverse-proxy"
    TLS_CERT_FILE="$ROOT_DIR/certs/proxy.crt" \
      TLS_KEY_FILE="$ROOT_DIR/certs/proxy.key" \
      go run .
  ) >"$ROOT_DIR/.smoke-proxy.log" 2>&1 &
  PROXY_PID="$!"
fi

wait_for_port "localhost" "$PROXY_PORT" "reverse proxy"

ADMIN_TOKEN="$(get_token "$ADMIN_USERNAME" "$ADMIN_PASSWORD")"
USER_TOKEN="$(get_token "$USER_USERNAME" "$USER_PASSWORD")"

api_json POST "$ADMIN_TOKEN" "/users/me/sync" >/dev/null
api_json POST "$USER_TOKEN" "/users/me/sync" >/dev/null

TEAM_ID="$(
  api_json GET "$ADMIN_TOKEN" "/admin/teams" |
    jq -r --arg team_name "$TEAM_NAME" '.[] | select(.name == $team_name) | .id' |
    head -n 1
)"

if [[ -z "$TEAM_ID" ]]; then
  echo "Could not resolve team id for ${TEAM_NAME}" >&2
  exit 1
fi

DATABASE_ID="$(
  api_json GET "$ADMIN_TOKEN" "/admin/databases" |
    jq -r \
      --arg database_name "$SMOKE_DB_NAME" \
      --arg host "$REGISTERED_HOST" \
      --argjson port "$REGISTERED_PORT" \
      '.[] | select(.databaseName == $database_name and .host == $host and .port == $port) | .id' |
    head -n 1
)"

database_payload="$(jq -nc \
  --arg engine "$REGISTERED_ENGINE" \
  --arg host "$REGISTERED_HOST" \
  --argjson port "$REGISTERED_PORT" \
  --arg technical_user "$TECHNICAL_USER" \
  --arg technical_password "$TECHNICAL_PASSWORD" \
  --arg database_name "$SMOKE_DB_NAME" \
  '{
    engine: $engine,
    host: $host,
    port: $port,
    technicalUser: $technical_user,
    technicalPassword: $technical_password,
    databaseName: $database_name
  }'
)"

if [[ -z "$DATABASE_ID" ]]; then
  DATABASE_ID="$(
    api_json POST "$ADMIN_TOKEN" "/admin/databases" "$database_payload" |
      jq -r '.id'
  )"
else
  api_json PUT "$ADMIN_TOKEN" "/admin/databases/${DATABASE_ID}" "$database_payload" >/dev/null
  api_json POST "$ADMIN_TOKEN" "/admin/databases/${DATABASE_ID}/reactivate" >/dev/null
fi

grant_payload="$(jq -nc \
  --arg team_id "$TEAM_ID" \
  --arg database_id "$DATABASE_ID" \
  '{ teamId: $team_id, databaseId: $database_id }'
)"

api_json PUT "$ADMIN_TOKEN" "/admin/team-database-grants" "$grant_payload" >/dev/null

if ! api_json GET "$USER_TOKEN" "/me/effective-access" | jq -e --arg database_id "$DATABASE_ID" \
  'map(select(.databaseId == $database_id)) | length == 1' >/dev/null; then
  echo "Active database did not appear in effective access before deactivation" >&2
  exit 1
fi

unused_otp="$(
  api_json POST "$USER_TOKEN" "/me/databases/${DATABASE_ID}/otp" |
    jq -r '.otp'
)"

api_json POST "$ADMIN_TOKEN" "/admin/databases/${DATABASE_ID}/deactivate" >/dev/null

if ! api_json GET "$USER_TOKEN" "/me/effective-access" | jq -e --arg database_id "$DATABASE_ID" \
  'map(select(.databaseId == $database_id)) | length == 0' >/dev/null; then
  echo "Inactive database still appeared in effective access" >&2
  exit 1
fi

otp_after_deactivate_status="$(api_status POST "$USER_TOKEN" "/me/databases/${DATABASE_ID}/otp")"
if [[ "$otp_after_deactivate_status" != "404" ]]; then
  echo "Expected OTP issuance to fail with 404 after deactivation, got ${otp_after_deactivate_status}" >&2
  exit 1
fi

if PGPASSWORD="$unused_otp" psql \
  "host=localhost port=${PROXY_PORT} dbname=${SMOKE_DB_NAME} user=${USER_EMAIL} sslmode=require" \
  -Atqc "SELECT 1" >/dev/null 2>&1; then
  echo "Unused OTP unexpectedly worked after database deactivation" >&2
  exit 1
fi

api_json POST "$ADMIN_TOKEN" "/admin/databases/${DATABASE_ID}/reactivate" >/dev/null

if ! api_json GET "$USER_TOKEN" "/me/effective-access" | jq -e --arg database_id "$DATABASE_ID" \
  'map(select(.databaseId == $database_id)) | length == 1' >/dev/null; then
  echo "Reactivated database did not return to effective access" >&2
  exit 1
fi

fresh_otp="$(
  api_json POST "$USER_TOKEN" "/me/databases/${DATABASE_ID}/otp" |
    jq -r '.otp'
)"

proxy_query_result="$(
  PGPASSWORD="$fresh_otp" psql \
    "host=localhost port=${PROXY_PORT} dbname=${SMOKE_DB_NAME} user=${USER_EMAIL} sslmode=require" \
    -Atqc "SELECT 1"
)"

if [[ "$proxy_query_result" != "1" ]]; then
  echo "Expected proxy query to return 1 after reactivation, got: ${proxy_query_result}" >&2
  exit 1
fi

echo "Smoke test passed: lifecycle deactivation/reactivation is enforced end to end through the live proxy path."
