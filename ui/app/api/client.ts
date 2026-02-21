import axios, { type AxiosInstance } from "axios"
import { config } from "~/config"

const apiClient: AxiosInstance = axios.create({
  baseURL: config.api.baseUrl,
  headers: {
    "Content-Type": "application/json",
  },
})

export const setAuthInterceptor = (
  getToken: () => string | undefined,
  refreshToken: () => Promise<boolean>
) => {
  apiClient.interceptors.request.use(
    async (requestConfig) => {
      await refreshToken()
      const token = getToken()
      if (token) {
        requestConfig.headers.Authorization = `Bearer ${token}`
      }
      return requestConfig
    },
    (error) => Promise.reject(error)
  )
}

export default apiClient
