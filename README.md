# db-watchdog

`db-watchdog` is a multi-module project built around database access control and observability.

## Root Makefile

The repository now exposes a root `Makefile`, so the common workflows for every module can be run without leaving the project root.

Start with:

```bash
make help
```

Common targets:

- `make bootstrap`
- `make server-run`
- `make ui-dev`
- `make proxy-run`
- `make build`
- `make check`

## Modules

- `reverse-proxy/`: Go PostgreSQL reverse proxy.
- `server/`: Scala 3 backend API.
- `ui/`: React Router frontend.
- `docker/`: local PostgreSQL and Keycloak setup.

## Local Dependencies

Start the local infrastructure:

```bash
docker compose up -d
```

This starts:

- main PostgreSQL on `localhost:54320`
- external PostgreSQL on `localhost:54321`
- Keycloak on `http://localhost:8180`

## Running Modules

### Backend

The backend uses `sbt` and reads configuration from [application.conf](/home/ciszko/Code/db-watchdog/server/src/main/resources/application.conf).

```bash
make server-run
```

By default it listens on `http://localhost:8080`.

The backend now validates Keycloak access tokens against the configured JWKS endpoint.
Caller identity, the required `team` claim, and the `DBA` role all come from validated token claims.
The user synchronization endpoint is `POST /api/v1/users/me/sync` and derives identity from the bearer token instead of a client payload.
The backend now also exposes:

- `GET /api/v1/admin/teams`
- `GET /api/v1/admin/users`
- `GET /api/v1/admin/databases`
- `POST /api/v1/admin/databases`
- `PUT /api/v1/admin/team-database-grants`
- `DELETE /api/v1/admin/team-database-grants/{teamId}/{databaseId}`
- `PUT /api/v1/admin/user-database-access-extensions`
- `DELETE /api/v1/admin/user-database-access-extensions/{userId}/{databaseId}`
- `GET /api/v1/admin/users/{userId}/effective-access`
- `GET /api/v1/me/effective-access`
- `POST /api/v1/me/databases/{databaseId}/otp`

Database responses never expose `technicalPassword`.
The backend now issues short-lived OTPs backed by stored SHA-256 hashes, but proxy-side OTP verification is still pending.

Backend tests are split into unit and integration suites:

```bash
make server-test
make server-it-test
make server-coverage
```

Integration tests use Testcontainers, so local Docker access is required.
Coverage reports can be generated locally and in CI, but there is no fixed `100%` coverage gate.

### Frontend

The frontend uses `pnpm`.

```bash
make ui-install
make ui-dev
```

Default frontend-related config:

- Keycloak URL: `http://localhost:8180`
- API base URL: `http://localhost:8080/api/v1`

During startup the UI authenticates through Keycloak and then calls `POST /users/me/sync` without sending identity fields in the request body.

### Reverse Proxy

The reverse proxy is a Go module.

```bash
make proxy-run
```

It currently accepts PostgreSQL client connections on local TCP port `5432` and connects to the external PostgreSQL instance on `localhost:54321`.
The default `proxy-run` target injects `certs/server.crt` and `certs/server.key`, and both paths can be overridden with `PROXY_TLS_CERT_FILE=...` and `PROXY_TLS_KEY_FILE=...`.
Infrastructure commands stay as plain `docker compose ...` from the repository root instead of being mirrored through `make`.

## Validation

### Backend

```bash
make server-scalafix
make server-format
make server-format-check
make server-test
make server-it-test
make server-coverage
make server-compile
```

### Frontend

```bash
make ui-lint
make ui-typecheck
make ui-build
```

### Reverse Proxy

```bash
make proxy-test
make proxy-build
```

### Cross-Module

```bash
make build
make check
```

## Project Notes

- SQL migrations live in `server/src/main/resources/db/migration/`.
- Backend Scala sources live in `server/src/main/scala/dbwatchdog/`.
- Frontend app code lives in `ui/app/`.
- Reverse proxy protocol logic lives in `reverse-proxy/protocol/postgres/`.
- Local Keycloak development data lives in `docker/keycloak/realm-export.json`.

## Shared Database Contract

The backend now persists the shared contract that later proxy work will read and write directly:

- `databases` with `technical_user`, `technical_password`, and `database_name`
- `team_database_grants`
- `user_database_access_extensions`
- `temporary_access_credentials`
- `database_sessions`

At this stage the backend now creates and invalidates OTP credentials in these tables, but proxy-side verification and session review APIs are still implemented in later PRs.

## Local Keycloak Contract

The local realm treats Keycloak as the source of truth for:

- the required `team` claim
- the `DBA` administrative realm role
- the JWT signature and audience contract used by the backend

The frontend development client includes a backend audience mapper so the backend can validate `aud = db-watchdog-backend`.

## Current State

- The backend has a validated auth boundary, token-derived user sync, administrative access APIs, effective-access resolution, and OTP issuance.
- The reverse proxy still contains TODOs around direct OTP verification against the system database and saving session information.
- Session review endpoints and database edit/delete flows are still pending on the backend side.
- The repository includes local certificates under `certs/` for development use.
