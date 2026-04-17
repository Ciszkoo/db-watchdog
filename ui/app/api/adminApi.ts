import apiClient from "./client"
import type { DatabaseResponse, EffectiveDatabaseAccess } from "./types"

export interface TeamResponse {
  id: string
  name: string
  createdAt: string
  updatedAt: string
}

export interface AdminUserResponse {
  id: string
  keycloakId: string
  email: string
  firstName: string
  lastName: string
  team: TeamResponse
  createdAt: string
  updatedAt: string
}

export interface AdminTeamDatabaseGrantResponse {
  id: string
  teamId: string
  databaseId: string
  createdAt: string
  updatedAt: string
}

export interface AdminUserDatabaseAccessExtensionResponse {
  id: string
  userId: string
  databaseId: string
  expiresAt: string | null
  createdAt: string
  updatedAt: string
}

export interface AdminDatabaseSessionResponse {
  id: string
  credentialId: string
  clientAddr: string
  startedAt: string
  endedAt: string | null
  bytesSent: number | null
  bytesReceived: number | null
  user: AdminUserResponse
  database: DatabaseResponse
}

export type AdminDatabaseSessionState = "all" | "open" | "closed"

export interface ListAdminSessionsParams {
  page?: number
  pageSize?: number
  userId?: string
  teamId?: string
  databaseId?: string
  state?: AdminDatabaseSessionState
  startedFrom?: string
  startedTo?: string
}

export interface AdminDatabaseSessionPageResponse {
  items: AdminDatabaseSessionResponse[]
  page: number
  pageSize: number
  totalCount: number
}

export interface CreateDatabaseInput {
  engine: string
  host: string
  port: number
  technicalUser: string
  technicalPassword: string
  databaseName: string
}

export interface UpdateDatabaseInput {
  engine: string
  host: string
  port: number
  technicalUser: string
  technicalPassword: string | null
  databaseName: string
}

export interface UpsertTeamDatabaseGrantInput {
  teamId: string
  databaseId: string
}

export interface UpsertUserDatabaseAccessExtensionInput {
  userId: string
  databaseId: string
  expiresAt: string | null
}

export const adminApi = {
  async listTeams(): Promise<TeamResponse[]> {
    const response = await apiClient.get<TeamResponse[]>("/admin/teams")
    return response.data
  },

  async listUsers(): Promise<AdminUserResponse[]> {
    const response = await apiClient.get<AdminUserResponse[]>("/admin/users")
    return response.data
  },

  async listDatabases(): Promise<DatabaseResponse[]> {
    const response = await apiClient.get<DatabaseResponse[]>("/admin/databases")
    return response.data
  },

  async createDatabase(input: CreateDatabaseInput): Promise<DatabaseResponse> {
    const response = await apiClient.post<DatabaseResponse>("/admin/databases", input)
    return response.data
  },

  async updateDatabase(
    databaseId: string,
    input: UpdateDatabaseInput
  ): Promise<DatabaseResponse> {
    const response = await apiClient.put<DatabaseResponse>(
      `/admin/databases/${databaseId}`,
      input
    )
    return response.data
  },

  async deactivateDatabase(databaseId: string): Promise<DatabaseResponse> {
    const response = await apiClient.post<DatabaseResponse>(
      `/admin/databases/${databaseId}/deactivate`
    )
    return response.data
  },

  async reactivateDatabase(databaseId: string): Promise<DatabaseResponse> {
    const response = await apiClient.post<DatabaseResponse>(
      `/admin/databases/${databaseId}/reactivate`
    )
    return response.data
  },

  async listTeamDatabaseGrants(): Promise<AdminTeamDatabaseGrantResponse[]> {
    const response = await apiClient.get<AdminTeamDatabaseGrantResponse[]>(
      "/admin/team-database-grants"
    )
    return response.data
  },

  async upsertTeamDatabaseGrant(input: UpsertTeamDatabaseGrantInput): Promise<void> {
    await apiClient.put("/admin/team-database-grants", input)
  },

  async deleteTeamDatabaseGrant(teamId: string, databaseId: string): Promise<void> {
    await apiClient.delete(`/admin/team-database-grants/${teamId}/${databaseId}`)
  },

  async listUserDatabaseAccessExtensions(): Promise<
    AdminUserDatabaseAccessExtensionResponse[]
  > {
    const response = await apiClient.get<AdminUserDatabaseAccessExtensionResponse[]>(
      "/admin/user-database-access-extensions"
    )
    return response.data
  },

  async upsertUserDatabaseAccessExtension(
    input: UpsertUserDatabaseAccessExtensionInput
  ): Promise<void> {
    await apiClient.put("/admin/user-database-access-extensions", input)
  },

  async deleteUserDatabaseAccessExtension(
    userId: string,
    databaseId: string
  ): Promise<void> {
    await apiClient.delete(
      `/admin/user-database-access-extensions/${userId}/${databaseId}`
    )
  },

  async listSessions(
    params: ListAdminSessionsParams = {}
  ): Promise<AdminDatabaseSessionPageResponse> {
    const response = await apiClient.get<AdminDatabaseSessionPageResponse>(
      "/admin/sessions",
      {
        params: compactAdminSessionParams(params),
      }
    )
    return response.data
  },

  async getEffectiveAccessForUser(userId: string): Promise<EffectiveDatabaseAccess[]> {
    const response = await apiClient.get<EffectiveDatabaseAccess[]>(
      `/admin/users/${userId}/effective-access`
    )
    return response.data
  },
}

function compactAdminSessionParams(params: ListAdminSessionsParams) {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== "")
  )
}
