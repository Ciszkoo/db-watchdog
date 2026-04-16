import { describe, expect, it } from "vitest"

import { buildConfig } from "~/config"

describe("runtime config transport warnings", () => {
  it("does not generate warnings for https URLs", () => {
    const result = buildConfig({
      VITE_KEYCLOAK_URL: "https://auth.example.test/realms/db-watchdog",
      VITE_API_URL: "https://api.example.test/api/v1",
    })

    expect(result.transportWarnings).toEqual([])
  })

  it("generates local-dev-only warnings for local http URLs", () => {
    const result = buildConfig({
      VITE_KEYCLOAK_URL: "http://localhost:8180/realms/db-watchdog",
      VITE_API_URL: "http://127.0.0.1:8080/api/v1",
    })

    expect(result.transportWarnings).toHaveLength(2)
    expect(result.transportWarnings[0]).toContain("acceptable only for local development")
    expect(result.transportWarnings[0]).toContain("VITE_KEYCLOAK_URL")
    expect(result.transportWarnings[1]).toContain("acceptable only for local development")
    expect(result.transportWarnings[1]).toContain("VITE_API_URL")
  })

  it("generates deployment warnings for non-local http URLs", () => {
    const result = buildConfig({
      VITE_KEYCLOAK_URL: "http://auth.example.test/realms/db-watchdog",
      VITE_API_URL: "http://example.test/api/v1",
    })

    expect(result.transportWarnings).toHaveLength(2)
    expect(result.transportWarnings[0]).toContain("not acceptable outside local development")
    expect(result.transportWarnings[0]).toContain("VITE_KEYCLOAK_URL")
    expect(result.transportWarnings[1]).toContain("not acceptable outside local development")
    expect(result.transportWarnings[1]).toContain("VITE_API_URL")
  })
})
