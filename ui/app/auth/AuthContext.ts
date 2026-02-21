import { createContext, useContext } from "react"
import type { UserInfo } from "./keycloak"

export interface AuthContextType {
  isAuthenticated: boolean
  isLoading: boolean
  user: UserInfo | null
  token: string | undefined
  logout: () => void
  refreshToken: () => Promise<boolean>
}

export const AuthContext = createContext<AuthContextType | undefined>(undefined)

export function useAuth(): AuthContextType {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider")
  }
  return context
}