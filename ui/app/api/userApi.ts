import apiClient from "./client"

export interface User {
  id: string,
  keycloakId: string,
  email: string,
  firstName: string,
  lastName: string,
  teamId: string,
  createdAt: Date,
  updatedAt: Date
}

export const userApi = {
  syncUser: async (): Promise<User> => {
    const response = await apiClient.post<User>('/users/me/sync')
    return response.data
  }
}
