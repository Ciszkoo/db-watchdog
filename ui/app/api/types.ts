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
  deactivatedAt: string | null
  isActive: boolean
}
