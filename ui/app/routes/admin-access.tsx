import axios from "axios"
import {
  useCallback,
  useEffect,
  useState,
  type FormEvent,
  type ReactNode,
} from "react"

import {
  adminApi,
  type AdminTeamDatabaseGrantResponse,
  type AdminUserDatabaseAccessExtensionResponse,
} from "~/api/adminApi"
import type { EffectiveDatabaseAccess } from "~/api/types"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Badge } from "~/components/ui/badge"
import { Button } from "~/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "~/components/ui/card"
import { Skeleton } from "~/components/ui/skeleton"
import { ACCESS_SOURCE_META } from "~/lib/accessSource"

type AccessPageState = {
  teams: Awaited<ReturnType<typeof adminApi.listTeams>>
  users: Awaited<ReturnType<typeof adminApi.listUsers>>
  databases: Awaited<ReturnType<typeof adminApi.listDatabases>>
  grants: Awaited<ReturnType<typeof adminApi.listTeamDatabaseGrants>>
  extensions: Awaited<ReturnType<typeof adminApi.listUserDatabaseAccessExtensions>>
}

type GrantFormState = {
  teamId: string
  databaseId: string
}

type ExtensionFormState = {
  userId: string
  databaseId: string
  expiresAtLocal: string
}

const emptyState: AccessPageState = {
  teams: [],
  users: [],
  databases: [],
  grants: [],
  extensions: [],
}

export default function AdminAccessPage() {
  const [state, setState] = useState<AccessPageState>(emptyState)
  const [isLoading, setIsLoading] = useState(true)
  const [pageError, setPageError] = useState<string | null>(null)
  const [selectedUserId, setSelectedUserId] = useState("")
  const [effectiveAccess, setEffectiveAccess] = useState<EffectiveDatabaseAccess[]>([])
  const [isLoadingPreview, setIsLoadingPreview] = useState(false)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [grantForm, setGrantForm] = useState<GrantFormState>({
    teamId: "",
    databaseId: "",
  })
  const [extensionForm, setExtensionForm] = useState<ExtensionFormState>({
    userId: "",
    databaseId: "",
    expiresAtLocal: "",
  })
  const [isSubmittingGrant, setIsSubmittingGrant] = useState(false)
  const [isSubmittingExtension, setIsSubmittingExtension] = useState(false)
  const [grantError, setGrantError] = useState<string | null>(null)
  const [extensionError, setExtensionError] = useState<string | null>(null)
  const [grantSuccess, setGrantSuccess] = useState<string | null>(null)
  const [extensionSuccess, setExtensionSuccess] = useState<string | null>(null)

  const loadGrants = useCallback(async () => {
    const grants = await adminApi.listTeamDatabaseGrants()
    setState(current => ({ ...current, grants }))
    return grants
  }, [])

  const loadExtensions = useCallback(async () => {
    const extensions = await adminApi.listUserDatabaseAccessExtensions()
    setState(current => ({ ...current, extensions }))
    return extensions
  }, [])

  const loadEffectiveAccess = useCallback(async (userId: string) => {
    if (!userId) {
      setEffectiveAccess([])
      setPreviewError(null)
      return
    }

    setIsLoadingPreview(true)
    setPreviewError(null)

    try {
      const access = await adminApi.getEffectiveAccessForUser(userId)
      setEffectiveAccess(access)
    } catch (error) {
      console.error("Failed to load effective access preview", error)
      setPreviewError(
        "The selected user's effective access preview could not be loaded."
      )
    } finally {
      setIsLoadingPreview(false)
    }
  }, [])

  const loadPageData = useCallback(async () => {
    setPageError(null)

    try {
      const [teams, users, databases, grants, extensions] = await Promise.all([
        adminApi.listTeams(),
        adminApi.listUsers(),
        adminApi.listDatabases(),
        adminApi.listTeamDatabaseGrants(),
        adminApi.listUserDatabaseAccessExtensions(),
      ])

      setState({ teams, users, databases, grants, extensions })
      setGrantForm(current => ({
        teamId: pickExistingOrFirst(current.teamId, teams.map(team => team.id)),
        databaseId: pickExistingOrFirst(
          current.databaseId,
          databases.map(database => database.id)
        ),
      }))
      setExtensionForm(current => ({
        userId: pickExistingOrFirst(current.userId, users.map(user => user.id)),
        databaseId: pickExistingOrFirst(
          current.databaseId,
          databases.map(database => database.id)
        ),
        expiresAtLocal: current.expiresAtLocal,
      }))
      setSelectedUserId(current =>
        pickExistingOrFirst(current, users.map(user => user.id))
      )
    } catch (error) {
      console.error("Failed to load admin access data", error)
      setPageError(
        "The admin access page could not load teams, users, databases, or current access state."
      )
    } finally {
      setIsLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadPageData()
  }, [loadPageData])

  useEffect(() => {
    void loadEffectiveAccess(selectedUserId)
  }, [loadEffectiveAccess, selectedUserId])

  const teamById = new Map(state.teams.map(team => [team.id, team]))
  const userById = new Map(state.users.map(user => [user.id, user]))
  const databaseById = new Map(state.databases.map(database => [database.id, database]))
  const selectedUser = userById.get(selectedUserId)
  const canSubmitGrant = Boolean(grantForm.teamId && grantForm.databaseId)
  const canSubmitExtension = Boolean(extensionForm.userId && extensionForm.databaseId)

  const maybeRefreshPreviewForGrant = useCallback(
    async (teamId: string) => {
      if (selectedUser?.team.id === teamId) {
        await loadEffectiveAccess(selectedUser.id)
      }
    },
    [loadEffectiveAccess, selectedUser]
  )

  const maybeRefreshPreviewForExtension = useCallback(
    async (userId: string) => {
      if (selectedUserId === userId) {
        await loadEffectiveAccess(userId)
      }
    },
    [loadEffectiveAccess, selectedUserId]
  )

  const handleGrantSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setIsSubmittingGrant(true)
    setGrantError(null)
    setGrantSuccess(null)

    try {
      await adminApi.upsertTeamDatabaseGrant(grantForm)
      await loadGrants()
      await maybeRefreshPreviewForGrant(grantForm.teamId)
      setGrantSuccess("Team grant saved.")
    } catch (error) {
      console.error("Failed to upsert team grant", error)
      setGrantError(mapAdminError(error, "The team grant could not be saved."))
    } finally {
      setIsSubmittingGrant(false)
    }
  }

  const handleGrantDelete = async (grant: AdminTeamDatabaseGrantResponse) => {
    setGrantError(null)
    setGrantSuccess(null)

    try {
      await adminApi.deleteTeamDatabaseGrant(grant.teamId, grant.databaseId)
      await loadGrants()
      await maybeRefreshPreviewForGrant(grant.teamId)
      setGrantSuccess("Team grant removed.")
    } catch (error) {
      console.error("Failed to delete team grant", error)
      setGrantError(mapAdminError(error, "The team grant could not be removed."))
    }
  }

  const handleExtensionSubmit = async (
    event: FormEvent<HTMLFormElement>
  ) => {
    event.preventDefault()
    setIsSubmittingExtension(true)
    setExtensionError(null)
    setExtensionSuccess(null)

    try {
      await adminApi.upsertUserDatabaseAccessExtension({
        userId: extensionForm.userId,
        databaseId: extensionForm.databaseId,
        expiresAt: extensionForm.expiresAtLocal
          ? new Date(extensionForm.expiresAtLocal).toISOString()
          : null,
      })
      await loadExtensions()
      await maybeRefreshPreviewForExtension(extensionForm.userId)
      setExtensionSuccess("User extension saved.")
    } catch (error) {
      console.error("Failed to upsert user extension", error)
      setExtensionError(
        mapAdminError(error, "The user extension could not be saved.")
      )
    } finally {
      setIsSubmittingExtension(false)
    }
  }

  const handleExtensionDelete = async (
    extension: AdminUserDatabaseAccessExtensionResponse
  ) => {
    setExtensionError(null)
    setExtensionSuccess(null)

    try {
      await adminApi.deleteUserDatabaseAccessExtension(
        extension.userId,
        extension.databaseId
      )
      await loadExtensions()
      await maybeRefreshPreviewForExtension(extension.userId)
      setExtensionSuccess("User extension removed.")
    } catch (error) {
      console.error("Failed to delete user extension", error)
      setExtensionError(
        mapAdminError(error, "The user extension could not be removed.")
      )
    }
  }

  return (
    <main className="grid gap-6">
      <Card>
        <CardHeader>
          <CardTitle>Admin Access</CardTitle>
          <CardDescription>
            Review the current team grants and user extensions, then preview the
            effective access for one selected user.
          </CardDescription>
        </CardHeader>
      </Card>

      {isLoading ? (
        <LoadingState />
      ) : pageError ? (
        <Card>
          <CardContent className="space-y-4 p-6">
            <Alert variant="destructive">
              <AlertTitle>Admin access data unavailable</AlertTitle>
              <AlertDescription>{pageError}</AlertDescription>
            </Alert>
            <Button variant="outline" onClick={() => void loadPageData()}>
              Retry
            </Button>
          </CardContent>
        </Card>
      ) : (
        <>
          <Card>
            <CardHeader>
              <CardTitle>Team grants</CardTitle>
              <CardDescription>
                Grants apply to every user in the mapped Keycloak team.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              {grantSuccess ? (
                <Alert className="border-teal-200 bg-teal-50 text-teal-950">
                  <AlertTitle>Team grant updated</AlertTitle>
                  <AlertDescription>{grantSuccess}</AlertDescription>
                </Alert>
              ) : null}
              {grantError ? (
                <Alert variant="destructive">
                  <AlertTitle>Team grant request failed</AlertTitle>
                  <AlertDescription>{grantError}</AlertDescription>
                </Alert>
              ) : null}

              <form
                className="grid gap-4 lg:grid-cols-[1fr_1fr_auto]"
                onSubmit={handleGrantSubmit}
              >
                <SelectField
                  label="Team"
                  value={grantForm.teamId}
                  options={state.teams.map(team => ({
                    value: team.id,
                    label: team.name,
                  }))}
                  onChange={value =>
                    setGrantForm(current => ({ ...current, teamId: value }))
                  }
                />
                <SelectField
                  label="Grant database"
                  value={grantForm.databaseId}
                  options={state.databases.map(database => ({
                    value: database.id,
                    label: database.databaseName,
                  }))}
                  onChange={value =>
                    setGrantForm(current => ({ ...current, databaseId: value }))
                  }
                />
                <div className="flex items-end">
                  <Button
                    type="submit"
                    disabled={isSubmittingGrant || !canSubmitGrant}
                  >
                    {isSubmittingGrant ? "Saving..." : "Add team grant"}
                  </Button>
                </div>
              </form>

              {!canSubmitGrant ? (
                <Alert>
                  <AlertTitle>Grant setup needs a team and a database</AlertTitle>
                  <AlertDescription>
                    Load at least one team and one registered database before saving a
                    team grant.
                  </AlertDescription>
                </Alert>
              ) : null}

              {!state.grants.length ? (
                <Alert>
                  <AlertTitle>No team grants configured</AlertTitle>
                  <AlertDescription>
                    Add a first team-to-database mapping to make access flow through the
                    effective-access rules.
                  </AlertDescription>
                </Alert>
              ) : (
                <div className="overflow-x-auto">
                  <table className="min-w-full text-left text-sm">
                    <thead className="text-xs uppercase tracking-[0.18em] text-stone-500">
                      <tr>
                        <TableHead>Team</TableHead>
                        <TableHead>Database</TableHead>
                        <TableHead>Created</TableHead>
                        <TableHead>Updated</TableHead>
                        <TableHead>Action</TableHead>
                      </tr>
                    </thead>
                    <tbody>
                      {state.grants.map(grant => (
                        <tr key={grant.id} className="border-t border-stone-200/80">
                          <TableCell>
                            {teamById.get(grant.teamId)?.name ?? grant.teamId}
                          </TableCell>
                          <TableCell>
                            {databaseById.get(grant.databaseId)?.databaseName ??
                              grant.databaseId}
                          </TableCell>
                          <TableCell>{grant.createdAt}</TableCell>
                          <TableCell>{grant.updatedAt}</TableCell>
                          <TableCell>
                            <Button
                              variant="destructive"
                              size="sm"
                              onClick={() => void handleGrantDelete(grant)}
                            >
                              Remove
                            </Button>
                          </TableCell>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>User extensions</CardTitle>
              <CardDescription>
                Extensions add user-specific access and optional expiry on top of team
                grants.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              {extensionSuccess ? (
                <Alert className="border-teal-200 bg-teal-50 text-teal-950">
                  <AlertTitle>User extension updated</AlertTitle>
                  <AlertDescription>{extensionSuccess}</AlertDescription>
                </Alert>
              ) : null}
              {extensionError ? (
                <Alert variant="destructive">
                  <AlertTitle>User extension request failed</AlertTitle>
                  <AlertDescription>{extensionError}</AlertDescription>
                </Alert>
              ) : null}

              <form
                className="grid gap-4 lg:grid-cols-[1fr_1fr_1fr_auto]"
                onSubmit={handleExtensionSubmit}
              >
                <SelectField
                  label="User"
                  value={extensionForm.userId}
                  options={state.users.map(user => ({
                    value: user.id,
                    label: `${user.email} (${user.team.name})`,
                  }))}
                  onChange={value =>
                    setExtensionForm(current => ({ ...current, userId: value }))
                  }
                />
                <SelectField
                  label="Extension database"
                  value={extensionForm.databaseId}
                  options={state.databases.map(database => ({
                    value: database.id,
                    label: database.databaseName,
                  }))}
                  onChange={value =>
                    setExtensionForm(current => ({ ...current, databaseId: value }))
                  }
                />
                <InputField
                  label="Expires at"
                  type="datetime-local"
                  value={extensionForm.expiresAtLocal}
                  onChange={value =>
                    setExtensionForm(current => ({
                      ...current,
                      expiresAtLocal: value,
                    }))
                  }
                />
                <div className="flex items-end">
                  <Button
                    type="submit"
                    disabled={isSubmittingExtension || !canSubmitExtension}
                  >
                    {isSubmittingExtension ? "Saving..." : "Add or update extension"}
                  </Button>
                </div>
              </form>

              {!canSubmitExtension ? (
                <Alert>
                  <AlertTitle>Extension setup needs a user and a database</AlertTitle>
                  <AlertDescription>
                    Load at least one user and one registered database before saving a
                    user extension.
                  </AlertDescription>
                </Alert>
              ) : null}

              {!state.extensions.length ? (
                <Alert>
                  <AlertTitle>No user extensions configured</AlertTitle>
                  <AlertDescription>
                    Add a user-specific extension when a database needs to be granted
                    outside the base team policy.
                  </AlertDescription>
                </Alert>
              ) : (
                <div className="overflow-x-auto">
                  <table className="min-w-full text-left text-sm">
                    <thead className="text-xs uppercase tracking-[0.18em] text-stone-500">
                      <tr>
                        <TableHead>User</TableHead>
                        <TableHead>Team</TableHead>
                        <TableHead>Database</TableHead>
                        <TableHead>Expiry</TableHead>
                        <TableHead>Created</TableHead>
                        <TableHead>Updated</TableHead>
                        <TableHead>Action</TableHead>
                      </tr>
                    </thead>
                    <tbody>
                      {state.extensions.map(extension => {
                        const user = userById.get(extension.userId)

                        return (
                          <tr
                            key={extension.id}
                            className="border-t border-stone-200/80"
                          >
                            <TableCell>{user?.email ?? extension.userId}</TableCell>
                            <TableCell>{user?.team.name ?? "Unknown team"}</TableCell>
                            <TableCell>
                              {databaseById.get(extension.databaseId)?.databaseName ??
                                extension.databaseId}
                            </TableCell>
                            <TableCell>{extension.expiresAt ?? "No expiry"}</TableCell>
                            <TableCell>{extension.createdAt}</TableCell>
                            <TableCell>{extension.updatedAt}</TableCell>
                            <TableCell>
                              <Button
                                variant="destructive"
                                size="sm"
                                onClick={() => void handleExtensionDelete(extension)}
                              >
                                Remove
                              </Button>
                            </TableCell>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Effective-access preview</CardTitle>
              <CardDescription>
                Inspect the merged access view for one user, including team grants and
                active extensions.
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <SelectField
                label="Selected user"
                value={selectedUserId}
                options={state.users.map(user => ({
                  value: user.id,
                  label: `${user.email} (${user.team.name})`,
                }))}
                onChange={value => setSelectedUserId(value)}
              />

              {previewError ? (
                <div className="space-y-4">
                  <Alert variant="destructive">
                    <AlertTitle>Effective access unavailable</AlertTitle>
                    <AlertDescription>{previewError}</AlertDescription>
                  </Alert>
                  <Button
                    variant="outline"
                    onClick={() => void loadEffectiveAccess(selectedUserId)}
                  >
                    Retry preview
                  </Button>
                </div>
              ) : isLoadingPreview ? (
                <div className="grid gap-3">
                  {[0, 1].map(index => (
                    <Skeleton key={index} className="h-16 rounded-[1rem]" />
                  ))}
                </div>
              ) : !selectedUser ? (
                <Alert>
                  <AlertTitle>No users available</AlertTitle>
                  <AlertDescription>
                    Synchronize users through authentication before previewing effective
                    access.
                  </AlertDescription>
                </Alert>
              ) : !effectiveAccess.length ? (
                <Alert>
                  <AlertTitle>No effective access for selected user</AlertTitle>
                  <AlertDescription>
                    The current grants and extensions do not provide any active database
                    access for {selectedUser.email}.
                  </AlertDescription>
                </Alert>
              ) : (
                <div className="overflow-x-auto">
                  <table className="min-w-full text-left text-sm">
                    <thead className="text-xs uppercase tracking-[0.18em] text-stone-500">
                      <tr>
                        <TableHead>Database</TableHead>
                        <TableHead>Engine</TableHead>
                        <TableHead>Host</TableHead>
                        <TableHead>Port</TableHead>
                        <TableHead>Access source</TableHead>
                        <TableHead>Extension expiry</TableHead>
                      </tr>
                    </thead>
                    <tbody>
                      {effectiveAccess.map(item => {
                        const accessMeta = ACCESS_SOURCE_META[item.accessSource] ?? {
                          label: item.accessSource,
                          variant: "outline" as const,
                        }

                        return (
                          <tr
                            key={item.databaseId}
                            className="border-t border-stone-200/80"
                          >
                            <TableCell>{item.databaseName}</TableCell>
                            <TableCell>{item.engine}</TableCell>
                            <TableCell>{item.host}</TableCell>
                            <TableCell>{item.port}</TableCell>
                            <TableCell>
                              <Badge variant={accessMeta.variant}>
                                {accessMeta.label}
                              </Badge>
                            </TableCell>
                            <TableCell>
                              {item.extensionExpiresAt ?? "No expiry"}
                            </TableCell>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </main>
  )
}

function LoadingState() {
  return (
    <Card>
      <CardContent className="grid gap-3 p-6">
        {[0, 1, 2, 3].map(index => (
          <Skeleton key={index} className="h-20 rounded-[1rem]" />
        ))}
      </CardContent>
    </Card>
  )
}

function SelectField({
  label,
  onChange,
  options,
  value,
}: {
  label: string
  value: string
  options: Array<{ value: string; label: string }>
  onChange: (value: string) => void
}) {
  return (
    <label className="grid gap-2 text-sm font-medium text-stone-800">
      <span>{label}</span>
      <select
        className="rounded-[1rem] border border-stone-300 bg-white px-4 py-3 text-sm outline-none transition focus:border-stone-500"
        value={value}
        onChange={event => onChange(event.target.value)}
      >
        {options.map(option => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  )
}

function InputField({
  label,
  onChange,
  type = "text",
  value,
}: {
  label: string
  value: string
  type?: string
  onChange: (value: string) => void
}) {
  return (
    <label className="grid gap-2 text-sm font-medium text-stone-800">
      <span>{label}</span>
      <input
        className="rounded-[1rem] border border-stone-300 bg-white px-4 py-3 text-sm outline-none transition focus:border-stone-500"
        type={type}
        value={value}
        onChange={event => onChange(event.target.value)}
      />
    </label>
  )
}

function TableHead({ children }: { children: ReactNode }) {
  return <th className="px-3 py-3 font-semibold">{children}</th>
}

function TableCell({ children }: { children: ReactNode }) {
  return <td className="px-3 py-3 align-top text-stone-700">{children}</td>
}

function pickExistingOrFirst(current: string, values: string[]): string {
  if (current && values.includes(current)) {
    return current
  }

  return values[0] ?? ""
}

function mapAdminError(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error) && typeof error.response?.data === "string") {
    return error.response.data
  }

  return fallback
}
