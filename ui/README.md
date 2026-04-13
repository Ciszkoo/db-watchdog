# UI Module

This module contains the React Router frontend for `db-watchdog`.

## Preferred Workflow

Run frontend commands from the repository root through the shared `Makefile`:

```bash
make ui-install
make ui-dev
make ui-lint
make ui-typecheck
make ui-build
```

List every available root target with:

```bash
make help
```

## Direct Module Commands

If you need to work inside `ui/` directly, the equivalent commands are:

```bash
pnpm install
pnpm dev
pnpm lint
pnpm typecheck
pnpm build
pnpm start
```

## Runtime Defaults

- frontend dev server: `http://localhost:5173`
- Keycloak URL: `http://localhost:8180`
- backend API base URL: `http://localhost:8080/api/v1`

The frontend authenticates through Keycloak and synchronizes the current user with `POST /users/me/sync` after startup.
