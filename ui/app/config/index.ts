export const config = {
  keycloak: {
    url: import.meta.env.VITE_KEYCLOAK_URL || 'http://localhost:8180',
    realm: import.meta.env.VITE_KEYCLOAK_REALM || 'db-watchdog',
    clientId: import.meta.env.VITE_KEYCLOAK_ID || 'db-watchdog-frontend',
  },
  api: {
    baseUrl: import.meta.env.VITE_API_URL || 'http://localhost:8080/api/v1'
  }
}