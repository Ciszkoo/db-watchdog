SHELL := /bin/bash

.DEFAULT_GOAL := help

ROOT_DIR := $(abspath $(CURDIR))
DOCKER_COMPOSE := docker compose
GO := go
SBT := sbt
PNPM := pnpm

PROXY_DIR := reverse-proxy
SERVER_DIR := server
UI_DIR := ui

PROXY_TLS_CERT_FILE ?= $(ROOT_DIR)/certs/server.crt
PROXY_TLS_KEY_FILE ?= $(ROOT_DIR)/certs/server.key

.PHONY: \
	help \
	require-go require-sbt require-pnpm require-docker \
	bootstrap proxy-deps server-deps ui-install \
	infra-up infra-down infra-ps infra-logs \
	proxy-run proxy-test proxy-build proxy-check \
	server-run server-scalafix server-format server-format-check server-test server-it-test server-coverage server-compile server-check \
	ui-dev ui-start ui-lint ui-lint-fix ui-typecheck ui-build ui-check \
	build check

help: ## Show available root targets.
	@awk 'BEGIN {FS = ":.*## "}; /^[a-zA-Z0-9_.-]+:.*## / { printf "%-20s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

require-go:
	@command -v $(GO) >/dev/null 2>&1 || { echo "Missing required tool in PATH: $(GO)"; exit 1; }

require-sbt:
	@command -v $(SBT) >/dev/null 2>&1 || { echo "Missing required tool in PATH: $(SBT)"; exit 1; }

require-pnpm:
	@command -v $(PNPM) >/dev/null 2>&1 || { echo "Missing required tool in PATH: $(PNPM)"; exit 1; }

require-docker:
	@command -v docker >/dev/null 2>&1 || { echo "Missing required tool in PATH: docker"; exit 1; }

bootstrap: proxy-deps server-deps ui-install ## Download module dependencies needed for local work.

proxy-deps: require-go ## Download Go dependencies for the reverse proxy.
	cd "$(PROXY_DIR)" && $(GO) mod download

server-deps: require-sbt ## Download Scala dependencies for the backend.
	cd "$(SERVER_DIR)" && $(SBT) update

ui-install: require-pnpm ## Install frontend dependencies with pnpm.
	cd "$(UI_DIR)" && $(PNPM) install

infra-up: require-docker ## Start local infrastructure containers in the background.
	$(DOCKER_COMPOSE) up -d

infra-down: require-docker ## Stop local infrastructure containers.
	$(DOCKER_COMPOSE) down

infra-ps: require-docker ## Show local infrastructure container status.
	$(DOCKER_COMPOSE) ps

infra-logs: require-docker ## Stream local infrastructure logs.
	$(DOCKER_COMPOSE) logs -f

proxy-run: require-go ## Run the reverse proxy with the default local TLS certificate.
	cd "$(PROXY_DIR)" && TLS_CERT_FILE="$(PROXY_TLS_CERT_FILE)" TLS_KEY_FILE="$(PROXY_TLS_KEY_FILE)" $(GO) run .

proxy-test: require-go ## Run reverse proxy Go tests.
	cd "$(PROXY_DIR)" && $(GO) test ./...

proxy-build: require-go ## Build the reverse proxy module.
	cd "$(PROXY_DIR)" && $(GO) build ./...

proxy-check: proxy-test proxy-build ## Validate the reverse proxy module.

server-run: require-sbt ## Run the Scala backend.
	cd "$(SERVER_DIR)" && $(SBT) run

server-scalafix: require-sbt ## Run Scalafix on the backend project.
	cd "$(SERVER_DIR)" && $(SBT) scalafix

server-format: require-sbt ## Format backend Scala sources, including test scopes.
	cd "$(SERVER_DIR)" && $(SBT) scalafmt "Test / scalafmt" "IntegrationTest / scalafmt"

server-format-check: require-sbt ## Check backend Scala formatting for main and test scopes.
	cd "$(SERVER_DIR)" && $(SBT) scalafmtCheck "Test / scalafmtCheck" "IntegrationTest / scalafmtCheck"

server-test: require-sbt ## Run backend unit tests.
	cd "$(SERVER_DIR)" && $(SBT) test

server-it-test: require-sbt ## Run backend integration tests.
	cd "$(SERVER_DIR)" && $(SBT) "IntegrationTest / test"

server-coverage: require-sbt ## Generate backend coverage report for unit and integration tests.
	cd "$(SERVER_DIR)" && $(SBT) coverage test "IntegrationTest / test" coverageReport

server-compile: require-sbt ## Compile the backend.
	cd "$(SERVER_DIR)" && $(SBT) compile

server-check: server-format-check server-test server-it-test server-compile ## Validate the backend without mutating source files.

ui-dev: require-pnpm ## Run the frontend development server.
	cd "$(UI_DIR)" && $(PNPM) dev

ui-start: require-pnpm ## Run the built frontend server.
	cd "$(UI_DIR)" && $(PNPM) start

ui-lint: require-pnpm ## Run frontend linting.
	cd "$(UI_DIR)" && $(PNPM) lint

ui-lint-fix: require-pnpm ## Apply frontend lint fixes.
	cd "$(UI_DIR)" && $(PNPM) lint:fix

ui-typecheck: require-pnpm ## Run frontend type generation and TypeScript checks.
	cd "$(UI_DIR)" && $(PNPM) typecheck

ui-build: require-pnpm ## Build the frontend.
	cd "$(UI_DIR)" && $(PNPM) build

ui-check: ui-lint ui-typecheck ui-build ## Validate the frontend module.

build: proxy-build server-compile ui-build ## Build every application module.

check: proxy-check server-check ui-check ## Run the full cross-module validation suite.
