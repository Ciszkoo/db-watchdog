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
- `make e2e-test`
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

## Transport Security Contract

This section is the source of truth for the current transport-security expectations in `db-watchdog`.
This PR does not add end-to-end TLS on every hop. It documents the current contract, the local-development allowances, and the known limits of the current version.

### `browser -> ui`

- Current implementation: the UI is commonly served over `http://localhost` in local development.
- Local development: plain HTTP on `localhost` is acceptable.
- Deployment requirement: `https` is required outside local development.
- Not currently implemented by the app itself: certificate provisioning and TLS termination policy for deployed UI hosting.

### `ui -> backend`

- Current implementation: the UI defaults to `http://localhost:8080/api/v1`.
- Local development: plain HTTP to `localhost:8080` is acceptable.
- Deployment requirement: `https` is required outside local development.
- Not currently implemented by the app itself: backend HTTPS listener setup inside the Scala application.

### `backend -> keycloak`

- Current implementation: the backend validates tokens against the configured issuer and JWKS URL, and local development commonly uses `http://localhost:8180`.
- Local development: plain HTTP to the local Keycloak issuer and JWKS endpoint is acceptable.
- Deployment requirement: `https` is required for the configured issuer and JWKS URL outside local development.
- Not currently implemented by the app itself: automatic enforcement beyond startup warnings for insecure non-local Keycloak URLs.

### `client -> reverse-proxy`

- Current implementation: the reverse proxy expects TLS certificates from `TLS_CERT_FILE` and `TLS_KEY_FILE`, and PostgreSQL clients connect over TLS.
- Local development: a self-signed certificate is acceptable, and local smoke tests should use `sslmode=require`.
- Deployment requirement: a trusted certificate and correct host verification are required.
- Not currently implemented by the app itself: automated certificate issuance, trust distribution, or stricter client verification policy beyond the PostgreSQL TLS handshake.

### `reverse-proxy -> target database`

- Current implementation: the reverse proxy connects to the target PostgreSQL database over plain TCP.
- Local development: this is acceptable only on a trusted local developer network.
- Deployment requirement: this hop must stay on a trusted private network or be protected by infrastructure outside the application.
- Not currently implemented by the app itself: native TLS from the proxy to the target database. This is a conscious limitation of the current version.

The backend itself listens over HTTP. Outside local development, TLS termination is expected in front of the Scala application rather than from an HTTPS listener implemented inside the backend.

## Running Modules

### Backend

The backend uses `sbt` and reads configuration from [application.conf](/home/ciszko/Code/db-watchdog/server/src/main/resources/application.conf).

```bash
make server-run
```

By default it listens on `http://localhost:8080`.
The backend requires `TECHNICAL_CREDENTIALS_KEY`; `make server-run` injects a local development default.

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
The default `proxy-run` target injects `certs/proxy.crt` and `certs/proxy.key`, and both paths can be overridden with `PROXY_TLS_CERT_FILE=...` and `PROXY_TLS_KEY_FILE=...`.
The proxy also requires `TECHNICAL_CREDENTIALS_KEY`; `make proxy-run` injects the same local development default used by the backend.
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

## End-to-End Validation

The repository now includes one browser-driven end-to-end scenario that crosses the real local stack:

- Keycloak login
- React UI administration and user dashboard flows
- Scala backend APIs
- Go reverse proxy
- recorded PostgreSQL proxy sessions

Local requirements:

- Docker
- `psql`
- Go
- `sbt`
- `pnpm`

Run it from the repository root:

```bash
make e2e-test
```

The runner starts or reuses the local dependencies it needs, keeps temporary logs and PID files under `.e2e-tmp/`, and leaves Docker containers running locally after the test finishes. In CI, the dedicated E2E workflow performs the heavier `docker compose down -v` cleanup separately.

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

- `databases` with `technical_user`, encrypted `technical_password_ciphertext`, and `database_name`
- `team_database_grants`
- `user_database_access_extensions`
- `temporary_access_credentials`
- `database_sessions`

The backend creates and invalidates OTP credentials in these tables, the reverse proxy consumes them directly at connection time, and successful sessions are now written back for admin review. Both backend and proxy must share the same `TECHNICAL_CREDENTIALS_KEY` so stored database credentials can be encrypted and decrypted consistently.

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
- Hard delete flows, session filtering/pagination, credential-key rotation, and broader end-to-end coverage remain deferred.
- The repository includes local certificates under `certs/` for development use.
