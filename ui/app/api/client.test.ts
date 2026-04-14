import apiClient, { setAuthInterceptor } from "~/api/client"
import { describe, expect, it, vi } from "vitest"

describe("api client auth interceptors", () => {
  it("adds the bearer token after refreshing it", async () => {
    const refreshToken = vi.fn().mockResolvedValue(true)
    const handleUnauthorized = vi.fn()

    setAuthInterceptor(() => "bearer-token", refreshToken, handleUnauthorized)

    const response = await apiClient.get("/test", {
      adapter: async (config) => ({
        data: { authorization: config.headers.Authorization },
        status: 200,
        statusText: "OK",
        headers: {},
        config,
      }),
    })

    expect(refreshToken).toHaveBeenCalledTimes(1)
    expect(response.data.authorization).toBe("Bearer bearer-token")
    expect(handleUnauthorized).not.toHaveBeenCalled()
  })

  it("re-enters auth flow on a 401 response", async () => {
    const handleUnauthorized = vi.fn()

    setAuthInterceptor(
      () => "expired-token",
      vi.fn().mockResolvedValue(true),
      handleUnauthorized
    )

    await expect(
      apiClient.get("/test", {
        adapter: async (config) =>
          Promise.reject({
            isAxiosError: true,
            response: {
              data: { message: "Unauthorized" },
              status: 401,
              statusText: "Unauthorized",
              headers: {},
              config,
            },
            config,
          }),
      })
    ).rejects.toMatchObject({
      response: {
        status: 401,
      },
    })

    expect(handleUnauthorized).toHaveBeenCalledTimes(1)
  })
})
