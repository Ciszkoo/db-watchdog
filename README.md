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
- `GET /api/v1/admin/sessions`
- `GET /api/v1/admin/databases`
- `POST /api/v1/admin/databases`
- `PUT /api/v1/admin/databases/{databaseId}`
- `POST /api/v1/admin/databases/{databaseId}/deactivate`
- `POST /api/v1/admin/databases/{databaseId}/reactivate`
- `GET /api/v1/admin/team-database-grants`
- `PUT /api/v1/admin/team-database-grants`
- `DELETE /api/v1/admin/team-database-grants/{teamId}/{databaseId}`
- `GET /api/v1/admin/user-database-access-extensions`
- `PUT /api/v1/admin/user-database-access-extensions`
- `DELETE /api/v1/admin/user-database-access-extensions/{userId}/{databaseId}`
- `GET /api/v1/admin/users/{userId}/effective-access`
- `GET /api/v1/me/effective-access`
- `POST /api/v1/me/databases/{databaseId}/otp`

Database responses never expose `technicalPassword`.
The backend now issues short-lived OTPs backed by stored SHA-256 hashes, `GET /api/v1/admin/sessions` exposes the recorded proxy sessions for `DBA` review, and inactive databases stay visible in the admin registry while being excluded from effective-access resolution and OTP issuance.
`PUT /api/v1/admin/team-database-grants` and `PUT /api/v1/admin/user-database-access-extensions` now return `409 Conflict` when the target database is inactive.

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
- Proxy host shown in OTP instructions: `localhost`
- Proxy port shown in OTP instructions: `5432`

The frontend dashboard at `/` now lists the authenticated user’s effective database access, allows per-database OTP generation, and shows the exact proxy login parameters needed for manual connection:

- `host=<VITE_PROXY_HOST or localhost>`
- `port=<VITE_PROXY_PORT or 5432>`
- `user=<loginIdentifier>`
- `database=<registered databaseName>`

During startup the UI authenticates through Keycloak and then calls `POST /users/me/sync` without sending identity fields in the request body.

The UI is now role-aware from Keycloak token claims:

- every authenticated user sees `My Access` at `/`
- a user with the `DBA` realm role also sees:
  - `/admin/databases`
  - `/admin/access`
  - `/admin/sessions`

From the admin UI, a `DBA` can now:

- register databases from the browser
- edit registered databases from the browser
- deactivate and reactivate registered databases without losing grants or history
- review current team-to-database grants
- add and remove team grants
- review current per-user access extensions
- add, update, and remove user extensions
- preview one selected user’s effective access
- review recorded proxy sessions

Inactive databases remain visible in the admin registry and in historical grant/extension tables, but the UI no longer offers them in new grant or extension selectors.

Non-`DBA` users who navigate directly to `/admin/*` now get an explicit in-app access-denied screen instead of a silent redirect.

### Reverse Proxy

The reverse proxy is a Go module.

```bash
make proxy-run
```

It accepts PostgreSQL client connections on local TCP port `5432`, validates OTPs directly against the system database, resolves the backend target from the registered `databases` row, and records session lifecycle rows in `database_sessions`.
The default `proxy-run` target injects `certs/server.crt` and `certs/server.key`, and both paths can be overridden with `PROXY_TLS_CERT_FILE=...` and `PROXY_TLS_KEY_FILE=...`.
The proxy runtime also reads `SYSTEM_DB_DSN`, which defaults in local development to:

```bash
postgres://postgres:password@localhost:54320/db_watchdog?sslmode=disable
```

For local smoke tests, the PostgreSQL login shape through the proxy is:

- `user=<user email>`
- `password=<issued otp>`
- `dbname=<registered databaseName>`

If a database has been deactivated, previously issued but unused OTPs for that database no longer authenticate through the proxy and still surface as the same generic authentication failure.

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
make ui-test
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

The backend creates and invalidates OTP credentials in these tables, the reverse proxy consumes them directly at connection time, and successful sessions are now written back for admin review.

## CI

The repository now has a dedicated reverse-proxy GitHub Actions workflow at [.github/workflows/reverse-proxy-ci.yml](/home/ciszko/Code/db-watchdog/.github/workflows/reverse-proxy-ci.yml).
It runs `go test ./...` and `go build ./...` for `reverse-proxy/**` changes without coupling the Go checks to the Scala workflow.
The frontend now also has a dedicated workflow at [.github/workflows/ui-ci.yml](/home/ciszko/Code/db-watchdog/.github/workflows/ui-ci.yml), which runs `make ui-lint`, `make ui-typecheck`, `make ui-test`, and `make ui-build` for relevant UI changes.

## Local Keycloak Contract

The local realm treats Keycloak as the source of truth for:

- the required `team` claim
- the `DBA` administrative realm role
- the JWT signature and audience contract used by the backend

The frontend development client includes a backend audience mapper so the backend can validate `aud = db-watchdog-backend`.

## Current State

- The backend has a validated auth boundary, token-derived user sync, administrative access APIs, read/write access-state management, effective-access resolution, OTP issuance, and admin session review.
- The reverse proxy now verifies OTPs against the system database, resolves registered PostgreSQL targets dynamically, records session start/end metadata, and rejects inactive-database OTP consumption through the same generic auth failure path.
- The frontend now includes both the end-user OTP workflow and an operable `DBA` admin console for database registration, editing, reversible deactivation, access-state review, and session review.
- Hard delete flows, technical credential hardening, session filtering/pagination, and Playwright end-to-end coverage remain deferred.
- The repository includes local certificates under `certs/` for development use.
