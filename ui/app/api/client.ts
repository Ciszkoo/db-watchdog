import axios, { type AxiosInstance } from "axios"
import { config } from "~/config"

const apiClient: AxiosInstance = axios.create({
  baseURL: config.api.baseUrl,
  headers: {
    "Content-Type": "application/json",
  },
})

let requestInterceptorId: number | null = null
let responseInterceptorId: number | null = null
let unauthorizedHandled = false

export const setAuthInterceptor = (
  getToken: () => string | undefined,
  refreshToken: () => Promise<boolean>,
  handleUnauthorized: () => void
) => {
  if (requestInterceptorId !== null) {
    apiClient.interceptors.request.eject(requestInterceptorId)
  }

  if (responseInterceptorId !== null) {
    apiClient.interceptors.response.eject(responseInterceptorId)
  }

  unauthorizedHandled = false

  requestInterceptorId = apiClient.interceptors.request.use(
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

  responseInterceptorId = apiClient.interceptors.response.use(
    (response) => response,
    (error) => {
      if (
        axios.isAxiosError(error) &&
        error.response?.status === 401 &&
        !unauthorizedHandled
      ) {
        unauthorizedHandled = true
        handleUnauthorized()
      }

      return Promise.reject(error)
    }
  )
}

export default apiClient
