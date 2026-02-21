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
  const [user, setUser] = useState<UserInfo | null>(null)

  const syncUserWithBackend = useCallback(async (userInfo: UserInfo) => {
    try {
      await userApi.syncUser({
        keycloakId: userInfo.sub,
        email: userInfo.email,
        firstName: userInfo.firstName,
        lastName: userInfo.lastName,
        team: userInfo.team,
      })
    } catch (error) {
      console.error("Failed to sync user with backend:", error)
    }
  }, [])

  useEffect(() => {
    const init = async () => {
      try {
        const userInfo = await initKeycloak()

        if (!userInfo) {
          getKeycloak().login()
          return
        }

        setAuthInterceptor(getToken, () => updateToken(30))
        setIsAuthenticated(true)
        setUser(userInfo)
        await syncUserWithBackend(userInfo)
      } catch (error) {
        if (error instanceof MissingTeamError) {
          console.error("User has no team assigned")
          keycloakLogout()
          return
        }
        console.error("Auth initialization failed:", error)
        setIsAuthenticated(false)
      } finally {
        setIsLoading(false)
      }
    }

    init()
  }, [syncUserWithBackend])

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
        user,
        token: getKeycloak().token,
        logout,
        refreshToken,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}