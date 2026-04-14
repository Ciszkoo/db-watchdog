import {
  useCallback,
  useEffect,
  useState,
  type ReactNode,
} from "react"
import {
  getKeycloak,
  initKeycloak,
  logout as keycloakLogout,
  MissingTeamError,
  updateToken,
  type UserInfo,
} from "./keycloak"
import { userApi } from "~/api/userApi"
import { setAuthInterceptor } from "~/api/client"
import { getToken } from "./keycloak"
import { AuthContext } from "./AuthContext"

export function AuthProvider({ children }: { children: ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState(false)
  const [isLoading, setIsLoading] = useState(true)
  const [authError, setAuthError] = useState<string | null>(null)
  const [user, setUser] = useState<UserInfo | null>(null)

  const syncUserWithBackend = useCallback(async () => {
    try {
      await userApi.syncUser()
    } catch (error) {
      console.error("Failed to sync user with backend:", error)
    }
  }, [])

  const login = useCallback(async () => {
    setAuthError(null)
    await getKeycloak().login()
  }, [])

  const handleUnauthorized = useCallback(() => {
    setAuthError(null)
    setUser(null)
    setIsAuthenticated(false)
    void login()
  }, [login])

  useEffect(() => {
    const init = async () => {
      try {
        const userInfo = await initKeycloak()

        if (!userInfo) {
          await login()
          return
        }

        setAuthInterceptor(getToken, () => updateToken(30), handleUnauthorized)
        setAuthError(null)
        setIsAuthenticated(true)
        setUser(userInfo)
        await syncUserWithBackend()
      } catch (error) {
        if (error instanceof MissingTeamError) {
          console.error("User has no team assigned")
          keycloakLogout()
          return
        }
        console.error("Auth initialization failed:", error)
        setAuthError("Authentication couldn't be initialized. Try signing in again.")
        setIsAuthenticated(false)
        setUser(null)
      } finally {
        setIsLoading(false)
      }
    }

    init()
  }, [handleUnauthorized, login, syncUserWithBackend])

  useEffect(() => {
    if (!isAuthenticated) return
    const interval = setInterval(() => updateToken(60), 30000)
    return () => clearInterval(interval)
  }, [isAuthenticated])

  const logout = useCallback(() => {
    setUser(null)
    setIsAuthenticated(false)
    keycloakLogout()
  }, [])

  const refreshToken = useCallback(() => updateToken(30), [])

  return (
    <AuthContext.Provider
        value={{
          isAuthenticated,
          isLoading,
          authError,
          user,
          token: getKeycloak().token,
          login,
          logout,
          refreshToken,
        }}
    >
      {children}
    </AuthContext.Provider>
  )
}
