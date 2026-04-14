import apiClient from "./client"

export interface EffectiveDatabaseAccess {
  databaseId: string
  engine: string
  host: string
  port: number
  databaseName: string
  loginIdentifier: string
  accessSource: string
  extensionExpiresAt: string | null
}

export interface DatabaseResponse {
  id: string
  engine: string
  host: string
  port: number
  technicalUser: string
  databaseName: string
  createdAt: string
  updatedAt: string
}

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
