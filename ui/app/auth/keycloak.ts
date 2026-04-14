import Keycloak from "keycloak-js"
import { config } from "~/config"

export interface UserInfo {
  sub: string
  email: string
  firstName: string
  lastName: string
  preferredUsername: string
  emailVerified: boolean
  team: string
  roles: string[]
  isDba: boolean
}

export class MissingTeamError extends Error {
  constructor() {
    super("User is missing required 'team' attribute")
    this.name = "MissingTeamError"
  }
}

let _keycloak: Keycloak | null = null

export const getKeycloak = (): Keycloak => {
  if (!_keycloak) {
    _keycloak = new Keycloak({
      url: config.keycloak.url,
      realm: config.keycloak.realm,
      clientId: config.keycloak.clientId,
    })
  }
  return _keycloak
}

function readString(
  value: unknown
): string | undefined {
  return typeof value === "string" ? value : undefined
}

function parseRoles(data: Record<string, unknown>): string[] {
  const realmAccess = data.realm_access
  if (!realmAccess || typeof realmAccess !== "object" || Array.isArray(realmAccess)) {
    return []
  }

  const roles = (realmAccess as { roles?: unknown }).roles
  if (!Array.isArray(roles)) {
    return []
  }

  return roles.filter((role): role is string => typeof role === "string")
}

function parseUserInfo(
  data: Record<string, unknown>,
  tokenData: Record<string, unknown>
): UserInfo {
  const team = readString(data.team) ?? readString(tokenData.team)
  if (!team) throw new MissingTeamError()

  const roles = parseRoles(tokenData)

  return {
    sub: readString(data.sub) ?? readString(tokenData.sub) ?? "",
    email: readString(data.email) ?? readString(tokenData.email) ?? "",
    firstName:
      readString(data.given_name) ?? readString(tokenData.given_name) ?? "",
    lastName:
      readString(data.family_name) ?? readString(tokenData.family_name) ?? "",
    preferredUsername:
      readString(data.preferred_username) ??
      readString(tokenData.preferred_username) ??
      "",
    emailVerified:
      typeof data.email_verified === "boolean"
        ? data.email_verified
        : typeof tokenData.email_verified === "boolean"
          ? tokenData.email_verified
          : false,
    team,
    roles,
    isDba: roles.includes("DBA"),
  }
}

export const initKeycloak = async (): Promise<UserInfo | null> => {
  const keycloak = getKeycloak()

  const authenticated = await keycloak.init({
    onLoad: "check-sso",  // <-- nie robi redirect natychmiast
    silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
    checkLoginIframe: false,
    pkceMethod: "S256",
  })

  if (!authenticated) {
    return null
  }

  const parsedToken = (keycloak.tokenParsed ?? {}) as Record<string, unknown>

  try {
    const info = await keycloak.loadUserInfo()
    return parseUserInfo(info as Record<string, unknown>, parsedToken)
  } catch (error) {
    if (error instanceof MissingTeamError) throw error

    if (keycloak.tokenParsed) {
      return parseUserInfo(parsedToken, parsedToken)
    }

    throw new Error("Failed to load user info", { cause: error })
  }
}

export const getToken = (): string | undefined => getKeycloak().token

export const updateToken = async (minValidity = 30): Promise<boolean> => {
  try {
    return await getKeycloak().updateToken(minValidity)
  } catch {
    getKeycloak().login()
    return false
  }
}

export const logout = (): void => {
  getKeycloak().logout({ redirectUri: window.location.origin })
}
