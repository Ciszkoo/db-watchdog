import apiClient from "./client"

export interface User {
  id: string,
  keycloakId: string,
  email: string,
  firstName: string,
  lastName: string,
  team: string,
  createdAt: Date,
  updatedAt: Date
}

export interface SyncUserRequest {
  keycloakId: string,
  email: string,
  firstName: string,
  lastName: string,
  team: string
}

export const userApi = {
  syncUser: async (data: SyncUserRequest): Promise<User> => {
    const response = await apiClient.post<User>('/users/sync', data)
    return response.data
  }
}