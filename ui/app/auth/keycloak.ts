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

function parseUserInfo(data: Record<string, unknown>): UserInfo {
  const team = data.team as string | undefined
  if (!team) throw new MissingTeamError()

  return {
    sub: (data.sub as string) ?? "",
    email: (data.email as string) ?? "",
    firstName: (data.given_name as string) ?? "",
    lastName: (data.family_name as string) ?? "",
    preferredUsername: (data.preferred_username as string) ?? "",
    emailVerified: (data.email_verified as boolean) ?? false,
    team,
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

  try {
    const info = await keycloak.loadUserInfo()
    return parseUserInfo(info as Record<string, unknown>)
  } catch (error) {
    if (error instanceof MissingTeamError) throw error

    if (keycloak.tokenParsed) {
      return parseUserInfo(keycloak.tokenParsed as Record<string, unknown>)
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