#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

cleanup() {
  bash "${ROOT_DIR}/e2e/bin/cleanup-env.sh"
}

trap cleanup EXIT

bash "${ROOT_DIR}/e2e/bin/prepare-env.sh"

cd "${ROOT_DIR}/ui"
pnpm test:e2e
