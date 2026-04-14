const DEFAULT_PROXY_PORT = 5432

function parsePort(value: string | undefined): number {
  const parsed = Number.parseInt(value ?? "", 10)
  return Number.isFinite(parsed) ? parsed : DEFAULT_PROXY_PORT
}

export const config = {
  keycloak: {
    url: import.meta.env.VITE_KEYCLOAK_URL || 'http://localhost:8180',
    realm: import.meta.env.VITE_KEYCLOAK_REALM || 'db-watchdog',
    clientId: import.meta.env.VITE_KEYCLOAK_ID || 'db-watchdog-frontend',
  },
  api: {
    baseUrl: import.meta.env.VITE_API_URL || 'http://localhost:8080/api/v1'
  },
  proxy: {
    host: import.meta.env.VITE_PROXY_HOST || "localhost",
    port: parsePort(import.meta.env.VITE_PROXY_PORT),
  }
}
