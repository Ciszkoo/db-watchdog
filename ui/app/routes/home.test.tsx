import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type { IssuedOtp } from "~/api/accessApi"
import { accessApi } from "~/api/accessApi"
import { AuthContext, type AuthContextType } from "~/auth/AuthContext"
import Home from "~/routes/home"

vi.mock("~/api/accessApi", () => ({
  accessApi: {
    listEffectiveAccess: vi.fn(),
    issueOtp: vi.fn(),
  },
}))

const listEffectiveAccessMock = vi.mocked(accessApi.listEffectiveAccess)
const issueOtpMock = vi.mocked(accessApi.issueOtp)
let consoleErrorSpy: ReturnType<typeof vi.spyOn>

const defaultAuthContext: AuthContextType = {
  isAuthenticated: true,
  isLoading: false,
  isDba: false,
  authError: null,
  user: {
    sub: "user-1",
    email: "alex@example.com",
    firstName: "Alex",
    lastName: "Rivera",
    preferredUsername: "arivera",
    emailVerified: true,
    team: "Platform",
    roles: ["user"],
    isDba: false,
  },
  token: "token",
  login: vi.fn().mockResolvedValue(undefined),
  logout: vi.fn(),
  refreshToken: vi.fn().mockResolvedValue(true),
}

const accessRecord = {
  databaseId: "dddddddd-dddd-dddd-dddd-dddddddddddd",
  engine: "postgres",
  host: "db.internal",
  port: 5432,
  databaseName: "analytics",
  loginIdentifier: "alex@example.com",
  accessSource: "TEAM_AND_USER_EXTENSION",
  extensionExpiresAt: "2026-01-01T00:30:00Z",
}

const issuedOtp: IssuedOtp = {
  credentialId: "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
  otp: "temporary-otp",
  expiresAt: "2026-01-01T00:05:00Z",
  database: {
    id: accessRecord.databaseId,
    engine: "postgres",
    host: "db.internal",
    port: 5432,
    technicalUser: "technical_user",
    databaseName: "analytics",
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    deactivatedAt: null,
    isActive: true,
  },
}

describe("home dashboard", () => {
  beforeEach(() => {
    listEffectiveAccessMock.mockReset()
    issueOtpMock.mockReset()
    consoleErrorSpy = vi.spyOn(console, "error").mockImplementation(() => undefined)
  })

  afterEach(() => {
    vi.clearAllMocks()
    consoleErrorSpy.mockRestore()
  })

  it("renders a loading state while the initial access request is pending", () => {
    listEffectiveAccessMock.mockImplementation(() => new Promise(() => undefined))

    renderHome()

    expect(screen.getByText("Loading your access...")).toBeInTheDocument()
  })

  it("renders the empty access state with user context", async () => {
    listEffectiveAccessMock.mockResolvedValue([])

    renderHome()

    expect(await screen.findByText("No databases available")).toBeInTheDocument()
    expect(screen.getByText("Alex Rivera")).toBeInTheDocument()
    expect(screen.getByText("alex@example.com")).toBeInTheDocument()
    expect(screen.getByText("Platform")).toBeInTheDocument()
  })

  it("renders the access list and shows proxy details after OTP issuance", async () => {
    listEffectiveAccessMock.mockResolvedValue([accessRecord])
    issueOtpMock.mockResolvedValue(issuedOtp)

    renderHome()

    expect(await screen.findByText("analytics")).toBeInTheDocument()
    expect(screen.getAllByText("Team grant + extension").length).toBeGreaterThan(0)
    expect(screen.getByText("db.internal")).toBeInTheDocument()

    fireEvent.click(screen.getByRole("button", { name: "Generate OTP" }))

    await waitFor(() =>
      expect(issueOtpMock).toHaveBeenCalledWith(accessRecord.databaseId)
    )

    expect(await screen.findByText("temporary-otp")).toBeInTheDocument()
    expect(screen.getByText("localhost")).toBeInTheDocument()
    expect(screen.getAllByText("alex@example.com").length).toBeGreaterThan(0)
  })

  it("renders an inline OTP error when the database is no longer available", async () => {
    listEffectiveAccessMock.mockResolvedValue([accessRecord])
    issueOtpMock.mockRejectedValue(createAxiosError(404))

    renderHome()

    expect(await screen.findByText("analytics")).toBeInTheDocument()

    fireEvent.click(screen.getByRole("button", { name: "Generate OTP" }))

    expect(await screen.findByText("Database no longer available")).toBeInTheDocument()
    expect(
      screen.getByRole("button", { name: "Refresh access list" })
    ).toBeInTheDocument()
  })

  it("shows a retryable route-level error and retries the initial fetch", async () => {
    listEffectiveAccessMock
      .mockRejectedValueOnce(createAxiosError(503))
      .mockResolvedValueOnce([])

    renderHome()

    expect(
      await screen.findByRole("heading", {
        name: "Database access is temporarily unavailable",
      })
    ).toBeInTheDocument()

    fireEvent.click(screen.getByRole("button", { name: "Retry" }))

    await waitFor(() => expect(listEffectiveAccessMock).toHaveBeenCalledTimes(2))
    expect(await screen.findByText("No databases available")).toBeInTheDocument()
  })

})

function renderHome(context: Partial<AuthContextType> = {}) {
  const value: AuthContextType = {
    ...defaultAuthContext,
    ...context,
  }

  return render(
    <AuthContext.Provider value={value}>
      <Home />
    </AuthContext.Provider>
  )
}

function createAxiosError(status: number) {
  return {
    isAxiosError: true,
    response: {
      status,
    },
  }
}
