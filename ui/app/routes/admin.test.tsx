import { fireEvent, render, screen, waitFor, within } from "@testing-library/react"
import { createMemoryRouter, RouterProvider } from "react-router"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { accessApi } from "~/api/accessApi"
import { adminApi } from "~/api/adminApi"
import { AuthContext, type AuthContextType } from "~/auth/AuthContext"
import AdminAccessPage from "~/routes/admin-access"
import AdminDatabasesPage from "~/routes/admin-databases"
import AdminIndex from "~/routes/admin-index"
import AdminLayout from "~/routes/admin-layout"
import AdminSessionsPage from "~/routes/admin-sessions"
import AppShell from "~/routes/app-shell"
import Home from "~/routes/home"

vi.mock("~/api/adminApi", () => ({
  adminApi: {
    listTeams: vi.fn(),
    listUsers: vi.fn(),
    listDatabases: vi.fn(),
    createDatabase: vi.fn(),
    listTeamDatabaseGrants: vi.fn(),
    upsertTeamDatabaseGrant: vi.fn(),
    deleteTeamDatabaseGrant: vi.fn(),
    listUserDatabaseAccessExtensions: vi.fn(),
    upsertUserDatabaseAccessExtension: vi.fn(),
    deleteUserDatabaseAccessExtension: vi.fn(),
    listSessions: vi.fn(),
    getEffectiveAccessForUser: vi.fn(),
  },
}))

vi.mock("~/api/accessApi", () => ({
  accessApi: {
    listEffectiveAccess: vi.fn(),
    issueOtp: vi.fn(),
  },
}))

const listEffectiveAccessMock = vi.mocked(accessApi.listEffectiveAccess)
const listTeamsMock = vi.mocked(adminApi.listTeams)
const listUsersMock = vi.mocked(adminApi.listUsers)
const listDatabasesMock = vi.mocked(adminApi.listDatabases)
const createDatabaseMock = vi.mocked(adminApi.createDatabase)
const listTeamGrantsMock = vi.mocked(adminApi.listTeamDatabaseGrants)
const upsertTeamGrantMock = vi.mocked(adminApi.upsertTeamDatabaseGrant)
const deleteTeamGrantMock = vi.mocked(adminApi.deleteTeamDatabaseGrant)
const listExtensionsMock = vi.mocked(adminApi.listUserDatabaseAccessExtensions)
const upsertExtensionMock = vi.mocked(adminApi.upsertUserDatabaseAccessExtension)
const deleteExtensionMock = vi.mocked(adminApi.deleteUserDatabaseAccessExtension)
const listSessionsMock = vi.mocked(adminApi.listSessions)
const getEffectiveAccessForUserMock = vi.mocked(adminApi.getEffectiveAccessForUser)

const teamA = {
  id: "team-a",
  name: "Platform",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
}

const teamB = {
  id: "team-b",
  name: "Analytics",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
}

const userA = {
  id: "user-a",
  keycloakId: "kc-a",
  email: "alex@example.com",
  firstName: "Alex",
  lastName: "Rivera",
  team: teamA,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
}

const userB = {
  id: "user-b",
  keycloakId: "kc-b",
  email: "jordan@example.com",
  firstName: "Jordan",
  lastName: "Lane",
  team: teamB,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
}

const databaseA = {
  id: "database-a",
  engine: "postgres",
  host: "db.internal",
  port: 5432,
  technicalUser: "technical_user",
  databaseName: "analytics",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
}

const databaseB = {
  id: "database-b",
  engine: "postgres",
  host: "warehouse.internal",
  port: 5432,
  technicalUser: "warehouse_user",
  databaseName: "warehouse",
  createdAt: "2026-01-02T00:00:00Z",
  updatedAt: "2026-01-02T00:00:00Z",
}

const defaultAuthContext: AuthContextType = {
  isAuthenticated: true,
  isLoading: false,
  isDba: true,
  authError: null,
  user: {
    sub: "kc-auth",
    email: "admin@example.com",
    firstName: "Admin",
    lastName: "Operator",
    preferredUsername: "admin",
    emailVerified: true,
    team: "Platform",
    roles: ["DBA", "user"],
    isDba: true,
  },
  token: "token",
  login: vi.fn().mockResolvedValue(undefined),
  logout: vi.fn(),
  refreshToken: vi.fn().mockResolvedValue(true),
}

describe("admin routes", () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listEffectiveAccessMock.mockResolvedValue([])
    listTeamsMock.mockResolvedValue([teamA, teamB])
    listUsersMock.mockResolvedValue([userA, userB])
    listDatabasesMock.mockResolvedValue([databaseA, databaseB])
    createDatabaseMock.mockResolvedValue(databaseB)
    listTeamGrantsMock.mockResolvedValue([
      {
        id: "grant-a",
        teamId: teamA.id,
        databaseId: databaseA.id,
        createdAt: "2026-01-03T00:00:00Z",
        updatedAt: "2026-01-03T00:00:00Z",
      },
    ])
    upsertTeamGrantMock.mockResolvedValue(undefined)
    deleteTeamGrantMock.mockResolvedValue(undefined)
    listExtensionsMock.mockResolvedValue([
      {
        id: "extension-a",
        userId: userA.id,
        databaseId: databaseB.id,
        expiresAt: "2026-02-01T10:00:00Z",
        createdAt: "2026-01-04T00:00:00Z",
        updatedAt: "2026-01-04T00:00:00Z",
      },
    ])
    upsertExtensionMock.mockResolvedValue(undefined)
    deleteExtensionMock.mockResolvedValue(undefined)
    listSessionsMock.mockResolvedValue([
      {
        id: "session-a",
        credentialId: "credential-a",
        clientAddr: "127.0.0.1:5432",
        startedAt: "2026-01-05T10:00:00Z",
        endedAt: "2026-01-05T10:05:00Z",
        bytesSent: 120,
        bytesReceived: 240,
        user: userA,
        database: databaseA,
      },
    ])
    getEffectiveAccessForUserMock.mockResolvedValue([
      {
        databaseId: databaseA.id,
        engine: databaseA.engine,
        host: databaseA.host,
        port: databaseA.port,
        databaseName: databaseA.databaseName,
        loginIdentifier: userA.email,
        accessSource: "TEAM",
        extensionExpiresAt: null,
      },
    ])
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it("renders an access-denied state for non-DBA users on /admin/*", async () => {
    renderApp("/admin/databases", {
      isDba: false,
      user: {
        ...defaultAuthContext.user!,
        roles: ["user"],
        isDba: false,
      },
    })

    expect(await screen.findByText("Access denied")).toBeInTheDocument()
    expect(screen.getByRole("link", { name: "Back to My Access" })).toHaveAttribute(
      "href",
      "/"
    )
    expect(screen.queryByRole("link", { name: "Admin Databases" })).not.toBeInTheDocument()
  })

  it("shows DBA navigation links in the authenticated shell", async () => {
    renderApp("/")

    expect(await screen.findByRole("link", { name: "Admin Databases" })).toBeInTheDocument()
    expect(screen.getByRole("link", { name: "Admin Access" })).toBeInTheDocument()
    expect(screen.getByRole("link", { name: "Admin Sessions" })).toBeInTheDocument()
    expect(screen.getByText("DBA")).toBeInTheDocument()
  })

  it("loads databases and refreshes the list after a successful create", async () => {
    listDatabasesMock
      .mockResolvedValueOnce([databaseA])
      .mockResolvedValueOnce([databaseA, databaseB])

    renderApp("/admin/databases")

    expect(await screen.findByText("analytics")).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText("Host"), {
      target: { value: "warehouse.internal" },
    })
    fireEvent.change(screen.getByLabelText("Technical user"), {
      target: { value: "warehouse_user" },
    })
    fireEvent.change(screen.getByLabelText("Technical password"), {
      target: { value: "secret" },
    })
    fireEvent.change(screen.getByLabelText("Database name"), {
      target: { value: "warehouse" },
    })

    fireEvent.click(screen.getByRole("button", { name: "Create database" }))

    await waitFor(() =>
      expect(createDatabaseMock).toHaveBeenCalledWith({
        engine: "postgres",
        host: "warehouse.internal",
        port: 5432,
        technicalUser: "warehouse_user",
        technicalPassword: "secret",
        databaseName: "warehouse",
      })
    )

    expect(await screen.findByText("Database registered successfully.")).toBeInTheDocument()
    expect(await screen.findByText("warehouse")).toBeInTheDocument()
    expect(screen.getByLabelText("Host")).toHaveValue("")
  })

  it("keeps entered values when database creation fails", async () => {
    createDatabaseMock.mockRejectedValue(createAxiosError(400, "Duplicate database"))

    renderApp("/admin/databases")

    await screen.findByText("analytics")

    fireEvent.change(screen.getByLabelText("Host"), {
      target: { value: "duplicate.internal" },
    })
    fireEvent.change(screen.getByLabelText("Technical user"), {
      target: { value: "duplicate_user" },
    })
    fireEvent.change(screen.getByLabelText("Technical password"), {
      target: { value: "duplicate_secret" },
    })
    fireEvent.change(screen.getByLabelText("Database name"), {
      target: { value: "analytics-copy" },
    })

    fireEvent.click(screen.getByRole("button", { name: "Create database" }))

    expect(await screen.findByText("Duplicate database")).toBeInTheDocument()
    expect(screen.getByLabelText("Host")).toHaveValue("duplicate.internal")
    expect(screen.getByLabelText("Database name")).toHaveValue("analytics-copy")
  })

  it("renders current grants and refreshes the grant list after add and remove", async () => {
    listTeamGrantsMock
      .mockResolvedValueOnce([
        {
          id: "grant-a",
          teamId: teamA.id,
          databaseId: databaseA.id,
          createdAt: "2026-01-03T00:00:00Z",
          updatedAt: "2026-01-03T00:00:00Z",
        },
      ])
      .mockResolvedValueOnce([
        {
          id: "grant-a",
          teamId: teamA.id,
          databaseId: databaseA.id,
          createdAt: "2026-01-03T00:00:00Z",
          updatedAt: "2026-01-03T00:00:00Z",
        },
        {
          id: "grant-b",
          teamId: teamA.id,
          databaseId: databaseB.id,
          createdAt: "2026-01-06T00:00:00Z",
          updatedAt: "2026-01-06T00:00:00Z",
        },
      ])
      .mockResolvedValueOnce([
        {
          id: "grant-b",
          teamId: teamA.id,
          databaseId: databaseB.id,
          createdAt: "2026-01-06T00:00:00Z",
          updatedAt: "2026-01-06T00:00:00Z",
        },
      ])

    getEffectiveAccessForUserMock
      .mockResolvedValueOnce([
        {
          databaseId: databaseA.id,
          engine: databaseA.engine,
          host: databaseA.host,
          port: databaseA.port,
          databaseName: databaseA.databaseName,
          loginIdentifier: userA.email,
          accessSource: "TEAM",
          extensionExpiresAt: null,
        },
      ])
      .mockResolvedValueOnce([
        {
          databaseId: databaseA.id,
          engine: databaseA.engine,
          host: databaseA.host,
          port: databaseA.port,
          databaseName: databaseA.databaseName,
          loginIdentifier: userA.email,
          accessSource: "TEAM",
          extensionExpiresAt: null,
        },
        {
          databaseId: databaseB.id,
          engine: databaseB.engine,
          host: databaseB.host,
          port: databaseB.port,
          databaseName: databaseB.databaseName,
          loginIdentifier: userA.email,
          accessSource: "TEAM",
          extensionExpiresAt: null,
        },
      ])
      .mockResolvedValueOnce([
        {
          databaseId: databaseB.id,
          engine: databaseB.engine,
          host: databaseB.host,
          port: databaseB.port,
          databaseName: databaseB.databaseName,
          loginIdentifier: userA.email,
          accessSource: "TEAM",
          extensionExpiresAt: null,
        },
      ])

    renderApp("/admin/access")

    expect((await screen.findAllByText("2026-01-03T00:00:00Z")).length).toBeGreaterThan(0)
    expect(screen.getAllByText("warehouse").length).toBeGreaterThan(0)

    fireEvent.change(screen.getByLabelText("Grant database"), {
      target: { value: databaseB.id },
    })
    fireEvent.click(screen.getByRole("button", { name: "Add team grant" }))

    await waitFor(() =>
      expect(upsertTeamGrantMock).toHaveBeenCalledWith({
        teamId: teamA.id,
        databaseId: databaseB.id,
      })
    )

    expect(await screen.findByText("Team grant saved.")).toBeInTheDocument()
    await waitFor(() => expect(listTeamGrantsMock).toHaveBeenCalledTimes(2))

    fireEvent.click(screen.getAllByRole("button", { name: "Remove" })[0])

    await waitFor(() =>
      expect(deleteTeamGrantMock).toHaveBeenCalledWith(teamA.id, databaseA.id)
    )
    expect(await screen.findByText("Team grant removed.")).toBeInTheDocument()
    await waitFor(() => expect(listTeamGrantsMock).toHaveBeenCalledTimes(3))
    await waitFor(() =>
      expect(getEffectiveAccessForUserMock).toHaveBeenCalledTimes(3)
    )
  })

  it("refreshes extensions and the selected-user preview after extension mutations", async () => {
    const submittedExpiresAt = new Date("2026-02-02T09:30").toISOString()

    listExtensionsMock
      .mockResolvedValueOnce([
        {
          id: "extension-a",
          userId: userA.id,
          databaseId: databaseB.id,
          expiresAt: "2026-02-01T10:00:00Z",
          createdAt: "2026-01-04T00:00:00Z",
          updatedAt: "2026-01-04T00:00:00Z",
        },
      ])
      .mockResolvedValueOnce([
        {
          id: "extension-a",
          userId: userA.id,
          databaseId: databaseB.id,
          expiresAt: "2026-02-01T10:00:00Z",
          createdAt: "2026-01-04T00:00:00Z",
          updatedAt: "2026-01-04T00:00:00Z",
        },
        {
          id: "extension-b",
          userId: userA.id,
          databaseId: databaseA.id,
          expiresAt: "2026-02-02T08:30:00Z",
          createdAt: "2026-01-07T00:00:00Z",
          updatedAt: "2026-01-07T00:00:00Z",
        },
      ])
      .mockResolvedValueOnce([
        {
          id: "extension-b",
          userId: userA.id,
          databaseId: databaseA.id,
          expiresAt: "2026-02-02T08:30:00Z",
          createdAt: "2026-01-07T00:00:00Z",
          updatedAt: "2026-01-07T00:00:00Z",
        },
      ])

    getEffectiveAccessForUserMock
      .mockResolvedValueOnce([
        {
          databaseId: databaseA.id,
          engine: databaseA.engine,
          host: databaseA.host,
          port: databaseA.port,
          databaseName: databaseA.databaseName,
          loginIdentifier: userA.email,
          accessSource: "TEAM",
          extensionExpiresAt: null,
        },
      ])
      .mockResolvedValueOnce([
        {
          databaseId: databaseA.id,
          engine: databaseA.engine,
          host: databaseA.host,
          port: databaseA.port,
          databaseName: databaseA.databaseName,
          loginIdentifier: userA.email,
          accessSource: "TEAM_AND_USER_EXTENSION",
          extensionExpiresAt: "2026-02-02T08:30:00Z",
        },
      ])
      .mockResolvedValueOnce([
        {
          databaseId: databaseA.id,
          engine: databaseA.engine,
          host: databaseA.host,
          port: databaseA.port,
          databaseName: databaseA.databaseName,
          loginIdentifier: userA.email,
          accessSource: "TEAM_AND_USER_EXTENSION",
          extensionExpiresAt: "2026-02-02T08:30:00Z",
        },
      ])

    renderApp("/admin/access")

    expect(await screen.findByText("2026-02-01T10:00:00Z")).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText("Extension database"), {
      target: { value: databaseA.id },
    })
    fireEvent.change(screen.getByLabelText("Expires at"), {
      target: { value: "2026-02-02T09:30" },
    })
    fireEvent.click(screen.getByRole("button", { name: "Add or update extension" }))

    await waitFor(() =>
      expect(upsertExtensionMock).toHaveBeenCalledWith({
        userId: userA.id,
        databaseId: databaseA.id,
        expiresAt: submittedExpiresAt,
      })
    )

    expect(await screen.findByText("User extension saved.")).toBeInTheDocument()
    expect((await screen.findAllByText("2026-02-02T08:30:00Z")).length).toBeGreaterThan(0)
    await waitFor(() =>
      expect(getEffectiveAccessForUserMock).toHaveBeenCalledTimes(2)
    )
    await waitFor(() => expect(listExtensionsMock).toHaveBeenCalledTimes(2))

    const removableExtensionRow = screen.getByText("2026-02-01T10:00:00Z").closest("tr")
    expect(removableExtensionRow).not.toBeNull()
    fireEvent.click(
      within(removableExtensionRow as HTMLTableRowElement).getByRole("button", {
        name: "Remove",
      })
    )

    await waitFor(() =>
      expect(deleteExtensionMock).toHaveBeenCalledWith(userA.id, databaseB.id)
    )
    expect(await screen.findByText("User extension removed.")).toBeInTheDocument()
    await waitFor(() => expect(listExtensionsMock).toHaveBeenCalledTimes(3))
    await waitFor(() =>
      expect(getEffectiveAccessForUserMock).toHaveBeenCalledTimes(3)
    )
  })

  it("disables admin access mutations when prerequisite records are unavailable", async () => {
    listDatabasesMock.mockResolvedValueOnce([])
    listTeamGrantsMock.mockResolvedValueOnce([])
    listExtensionsMock.mockResolvedValueOnce([])
    getEffectiveAccessForUserMock.mockResolvedValueOnce([
      {
        databaseId: databaseA.id,
        engine: databaseA.engine,
        host: databaseA.host,
        port: databaseA.port,
        databaseName: databaseA.databaseName,
        loginIdentifier: userA.email,
        accessSource: "TEAM",
        extensionExpiresAt: null,
      },
    ])

    renderApp("/admin/access")

    expect(
      await screen.findByText("Grant setup needs a team and a database")
    ).toBeInTheDocument()
    expect(
      screen.getByText("Extension setup needs a user and a database")
    ).toBeInTheDocument()

    expect(
      screen.getByRole("button", { name: "Add team grant" })
    ).toBeDisabled()
    expect(
      screen.getByRole("button", { name: "Add or update extension" })
    ).toBeDisabled()

    expect(upsertTeamGrantMock).not.toHaveBeenCalled()
    expect(upsertExtensionMock).not.toHaveBeenCalled()
  })

  it("renders a populated session review table", async () => {
    renderApp("/admin/sessions")

    expect(await screen.findByText("127.0.0.1:5432")).toBeInTheDocument()
    expect(screen.getByText("alex@example.com")).toBeInTheDocument()
  })

  it("renders the empty session review state", async () => {
    listSessionsMock.mockResolvedValueOnce([])

    renderApp("/admin/sessions")

    expect(await screen.findByText("No recorded sessions")).toBeInTheDocument()
  })

  it("renders the auth recovery state from an admin route when authentication bootstrap failed", async () => {
    const login = vi.fn().mockResolvedValue(undefined)

    renderApp("/admin/databases", {
      isAuthenticated: false,
      authError: "Authentication couldn't be initialized. Try signing in again.",
      user: null,
      login,
    })

    expect(await screen.findByText("Authentication is required")).toBeInTheDocument()

    fireEvent.click(screen.getByRole("button", { name: "Sign in again" }))

    await waitFor(() => expect(login).toHaveBeenCalledTimes(1))
  })
})

function renderApp(path: string, context: Partial<AuthContextType> = {}) {
  const value: AuthContextType = {
    ...defaultAuthContext,
    ...context,
  }

  const router = createMemoryRouter(
    [
      {
        path: "/",
        element: <AppShell />,
        children: [
          {
            index: true,
            element: <Home />,
          },
          {
            path: "admin",
            element: <AdminLayout />,
            children: [
              { index: true, element: <AdminIndex /> },
              { path: "databases", element: <AdminDatabasesPage /> },
              { path: "access", element: <AdminAccessPage /> },
              { path: "sessions", element: <AdminSessionsPage /> },
            ],
          },
        ],
      },
    ],
    {
      initialEntries: [path],
    }
  )

  return render(
    <AuthContext.Provider value={value}>
      <RouterProvider router={router} />
    </AuthContext.Provider>
  )
}

function createAxiosError(status: number, data: string) {
  return {
    isAxiosError: true,
    response: {
      status,
      data,
    },
  }
}
