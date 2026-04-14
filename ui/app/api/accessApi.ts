import apiClient from "./client"
import type { DatabaseResponse, EffectiveDatabaseAccess } from "./types"

export interface IssuedOtp {
  credentialId: string
  otp: string
  expiresAt: string
  database: DatabaseResponse
}

export const accessApi = {
  async listEffectiveAccess(): Promise<EffectiveDatabaseAccess[]> {
    const response = await apiClient.get<EffectiveDatabaseAccess[]>("/me/effective-access")
    return response.data
  },

  async issueOtp(databaseId: string): Promise<IssuedOtp> {
    const response = await apiClient.post<IssuedOtp>(`/me/databases/${databaseId}/otp`)
    return response.data
  },
}
