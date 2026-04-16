const DEFAULT_PROXY_PORT = 5432
const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "::1"])

type RuntimeEnv = {
  VITE_KEYCLOAK_URL?: string
  VITE_KEYCLOAK_REALM?: string
  VITE_KEYCLOAK_ID?: string
  VITE_API_URL?: string
  VITE_PROXY_HOST?: string
  VITE_PROXY_PORT?: string
}

export type AppConfig = {
  keycloak: {
    url: string
    realm: string
    clientId: string
  }
  api: {
    baseUrl: string
  }
  proxy: {
    host: string
    port: number
  }
}

export type ConfigBuildResult = {
  config: AppConfig
  transportWarnings: string[]
}

function parsePort(value: string | undefined): number {
  const parsed = Number.parseInt(value ?? "", 10)
  return Number.isFinite(parsed) ? parsed : DEFAULT_PROXY_PORT
}

function transportSecurityWarning(
  settingName: string,
  urlValue: string
): string | null {
  const scheme = getScheme(urlValue)
  if (scheme === "https") {
    return null
  }

  if (scheme === "http" && isLocalHost(urlValue)) {
    return `Transport security warning: ${settingName} uses HTTP for a local endpoint. This is acceptable only for local development; use HTTPS outside local development.`
  }

  if (scheme === "http") {
    return `Transport security warning: ${settingName} uses HTTP for a non-local endpoint. This is not acceptable outside local development; configure HTTPS.`
  }

  return null
}

function getScheme(urlValue: string): string | null {
  const parsed = parseUrl(urlValue)
  return parsed ? parsed.protocol.replace(/:$/, "").toLowerCase() : null
}

function isLocalHost(urlValue: string): boolean {
  const parsed = parseUrl(urlValue)
  if (!parsed) {
    return false
  }

  const hostname = parsed.hostname.replace(/^\[(.*)\]$/, "$1").toLowerCase()
  return LOCAL_HOSTS.has(hostname)
}

function parseUrl(urlValue: string): URL | null {
  try {
    return new URL(urlValue)
  } catch {
    return null
  }
}

export function buildConfig(env: RuntimeEnv): ConfigBuildResult {
  const config: AppConfig = {
    keycloak: {
      url: env.VITE_KEYCLOAK_URL || "http://localhost:8180",
      realm: env.VITE_KEYCLOAK_REALM || "db-watchdog",
      clientId: env.VITE_KEYCLOAK_ID || "db-watchdog-frontend",
    },
    api: {
      baseUrl: env.VITE_API_URL || "http://localhost:8080/api/v1",
    },
    proxy: {
      host: env.VITE_PROXY_HOST || "localhost",
      port: parsePort(env.VITE_PROXY_PORT),
    },
  }

  const transportWarnings = [
    transportSecurityWarning("VITE_KEYCLOAK_URL", config.keycloak.url),
    transportSecurityWarning("VITE_API_URL", config.api.baseUrl),
  ].filter((warning): warning is string => warning !== null)

  return { config, transportWarnings }
}

const builtConfig = buildConfig({
  VITE_KEYCLOAK_URL: import.meta.env.VITE_KEYCLOAK_URL,
  VITE_KEYCLOAK_REALM: import.meta.env.VITE_KEYCLOAK_REALM,
  VITE_KEYCLOAK_ID: import.meta.env.VITE_KEYCLOAK_ID,
  VITE_API_URL: import.meta.env.VITE_API_URL,
  VITE_PROXY_HOST: import.meta.env.VITE_PROXY_HOST,
  VITE_PROXY_PORT: import.meta.env.VITE_PROXY_PORT,
})

export const config = builtConfig.config
export const transportWarnings = builtConfig.transportWarnings
