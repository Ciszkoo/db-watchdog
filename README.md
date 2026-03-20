# db-watchdog

`db-watchdog` is a multi-module project built around database access control and observability.

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
cd server
sbt run
```

By default it listens on `http://localhost:8080`.

### Frontend

The frontend uses `pnpm`.

```bash
cd ui
pnpm install
pnpm dev
```

Default frontend-related config:

- Keycloak URL: `http://localhost:8180`
- API base URL: `http://localhost:8080/api/v1`

### Reverse Proxy

The reverse proxy is a Go module.

```bash
cd reverse-proxy
TLS_CERT_FILE=../certs/server.crt TLS_KEY_FILE=../certs/server.key go run .
```

It currently accepts PostgreSQL client connections on local TCP port `5432` and connects to the external PostgreSQL instance on `localhost:54321`.

## Validation

### Backend

```bash
cd server
sbt scalafix
sbt scalafmt
sbt test
sbt compile
```

### Frontend

```bash
cd ui
pnpm lint
pnpm typecheck
pnpm build
```

### Reverse Proxy

```bash
cd reverse-proxy
go test ./...
go build ./...
```

## Project Notes

- SQL migrations live in `server/src/main/resources/db/migration/`.
- Backend Scala sources live in `server/src/main/scala/dbwatchdog/`.
- Frontend app code lives in `ui/app/`.
- Reverse proxy protocol logic lives in `reverse-proxy/protocol/postgres/`.

## Current State

- The reverse proxy contains TODOs around validating tokens against the system database and saving session information.
- The repository includes local certificates under `certs/` for development use.
