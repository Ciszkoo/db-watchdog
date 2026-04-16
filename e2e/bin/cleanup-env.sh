#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PID_DIR="${ROOT_DIR}/.e2e-tmp/pids"

cleanup_pid_file() {
  local pid_file="$1"

  if [[ ! -f "$pid_file" ]]; then
    return 0
  fi

  local pid
  pid="$(cat "$pid_file")"

  if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
    kill -- "-${pid}" >/dev/null 2>&1 || kill "$pid" >/dev/null 2>&1 || true

    for _ in {1..20}; do
      if ! kill -0 "$pid" >/dev/null 2>&1; then
        break
      fi

      sleep 1
    done

    if kill -0 "$pid" >/dev/null 2>&1; then
      kill -9 -- "-${pid}" >/dev/null 2>&1 || kill -9 "$pid" >/dev/null 2>&1 || true
    fi
  fi

  rm -f "$pid_file"
}

cleanup_pid_file "${PID_DIR}/ui.pid"
cleanup_pid_file "${PID_DIR}/proxy.pid"
cleanup_pid_file "${PID_DIR}/server.pid"
