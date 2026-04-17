import { useEffect, useState, type FormEvent, type ReactNode } from "react"
import { useSearchParams } from "react-router"

import {
  adminApi,
  type AdminDatabaseSessionPageResponse,
  type AdminDatabaseSessionState,
  type ListAdminSessionsParams,
} from "~/api/adminApi"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Button } from "~/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "~/components/ui/card"
import { Skeleton } from "~/components/ui/skeleton"

type SessionFiltersFormState = {
  teamId: string
  userId: string
  databaseId: string
  state: AdminDatabaseSessionState
  startedFrom: string
  startedTo: string
}

type AppliedSessionParams = ListAdminSessionsParams & {
  page: number
  pageSize: number
  state: AdminDatabaseSessionState
}

const defaultSessionPage: AdminDatabaseSessionPageResponse = {
  items: [],
  page: 1,
  pageSize: 25,
  totalCount: 0,
}

const defaultFilters: SessionFiltersFormState = {
  teamId: "",
  userId: "",
  databaseId: "",
  state: "all",
  startedFrom: "",
  startedTo: "",
}

const pageSizeOptions = [25, 50, 100]

export default function AdminSessionsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [teams, setTeams] = useState<Awaited<ReturnType<typeof adminApi.listTeams>>>([])
  const [users, setUsers] = useState<Awaited<ReturnType<typeof adminApi.listUsers>>>([])
  const [databases, setDatabases] = useState<Awaited<ReturnType<typeof adminApi.listDatabases>>>(
    []
  )
  const [sessionPage, setSessionPage] =
    useState<AdminDatabaseSessionPageResponse>(defaultSessionPage)
  const [draftFilters, setDraftFilters] = useState<SessionFiltersFormState>(() =>
    filtersFromParams(readAppliedParams(searchParams))
  )
  const [hasLoadedReferenceData, setHasLoadedReferenceData] = useState(false)
  const [isLoading, setIsLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [reloadNonce, setReloadNonce] = useState(0)

  const searchKey = searchParams.toString()
  const appliedParams = readAppliedParams(searchParams)
  const appliedFilters = filtersFromParams(appliedParams)
  const filteredUsers = users.filter(
    user => draftFilters.teamId === "" || user.team.id === draftFilters.teamId
  )
  const hasActiveFilters =
    appliedFilters.teamId !== "" ||
    appliedFilters.userId !== "" ||
    appliedFilters.databaseId !== "" ||
    appliedFilters.state !== "all" ||
    appliedFilters.startedFrom !== "" ||
    appliedFilters.startedTo !== ""
  const showingFrom =
    sessionPage.totalCount === 0 ? 0 : (sessionPage.page - 1) * sessionPage.pageSize + 1
  const showingTo = Math.min(
    sessionPage.page * sessionPage.pageSize,
    sessionPage.totalCount
  )
  const canGoPrevious = sessionPage.page > 1
  const canGoNext = showingTo < sessionPage.totalCount

  useEffect(() => {
    setDraftFilters(filtersFromParams(readAppliedParams(new URLSearchParams(searchKey))))
  }, [searchKey])

  useEffect(() => {
    let isCancelled = false

    const load = async () => {
      const params = readAppliedParams(new URLSearchParams(searchKey))

      setLoadError(null)
      setIsLoading(true)

      try {
        if (!hasLoadedReferenceData) {
          const [nextTeams, nextUsers, nextDatabases, nextSessionPage] =
            await Promise.all([
              adminApi.listTeams(),
              adminApi.listUsers(),
              adminApi.listDatabases(),
              adminApi.listSessions(params),
            ])

          if (isCancelled) {
            return
          }

          setTeams(nextTeams)
          setUsers(nextUsers)
          setDatabases(nextDatabases)

          if (recoverOutOfRangePage(searchKey, params, nextSessionPage, setSearchParams)) {
            setHasLoadedReferenceData(true)
            return
          }

          setSessionPage(nextSessionPage)
          setHasLoadedReferenceData(true)
          return
        }

        const nextSessionPage = await adminApi.listSessions(params)

        if (isCancelled) {
          return
        }

        if (recoverOutOfRangePage(searchKey, params, nextSessionPage, setSearchParams)) {
          return
        }

        setSessionPage(nextSessionPage)
      } catch (error) {
        if (isCancelled) {
          return
        }

        console.error("Failed to load sessions", error)
        setLoadError("Recorded proxy sessions could not be loaded.")
      } finally {
        if (!isCancelled) {
          setIsLoading(false)
        }
      }
    }

    void load()

    return () => {
      isCancelled = true
    }
  }, [reloadNonce, searchKey])

  const handleApplyFilters = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()

    const sanitizedFilters = sanitizeFilters(draftFilters, users)
    setDraftFilters(sanitizedFilters)
    setSearchParams(
      buildSearchParams({
        filters: sanitizedFilters,
        page: 1,
        pageSize: appliedParams.pageSize,
      })
    )
  }

  const handleResetFilters = () => {
    setDraftFilters(defaultFilters)
    setSearchParams(
      buildSearchParams({
        filters: defaultFilters,
        page: 1,
        pageSize: appliedParams.pageSize,
      })
    )
  }

  const handleRetry = () => {
    if (hasLoadedReferenceData) {
      setSessionPage(defaultSessionPage)
    }

    setReloadNonce(current => current + 1)
  }

  return (
    <main className="grid gap-6">
      <Card>
        <CardHeader>
          <CardTitle>Admin Sessions</CardTitle>
          <CardDescription>
            Review recorded proxy sessions with stable filters and newest-first
            pagination.
          </CardDescription>
        </CardHeader>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Filters</CardTitle>
          <CardDescription>
            Narrow the current session review without changing access decisions or
            proxy behavior.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form className="grid gap-4 lg:grid-cols-3" onSubmit={handleApplyFilters}>
            <SelectField
              label="Team"
              value={draftFilters.teamId}
              options={[
                { value: "", label: "All teams" },
                ...teams.map(team => ({ value: team.id, label: team.name })),
              ]}
              onChange={value =>
                setDraftFilters(current => {
                  const selectedUser = users.find(user => user.id === current.userId)
                  const nextUserId =
                    current.userId !== "" &&
                    value !== "" &&
                    selectedUser?.team.id !== value
                      ? ""
                      : current.userId

                  return {
                    ...current,
                    teamId: value,
                    userId: nextUserId,
                  }
                })
              }
            />

            <SelectField
              label="User"
              value={draftFilters.userId}
              options={[
                { value: "", label: "All users" },
                ...filteredUsers.map(user => ({
                  value: user.id,
                  label: `${user.email} (${user.team.name})`,
                })),
              ]}
              onChange={value =>
                setDraftFilters(current => ({
                  ...current,
                  userId: value,
                }))
              }
            />

            <SelectField
              label="Database"
              value={draftFilters.databaseId}
              options={[
                { value: "", label: "All databases" },
                ...databases.map(database => ({
                  value: database.id,
                  label: database.isActive
                    ? database.databaseName
                    : `${database.databaseName} (inactive)`,
                })),
              ]}
              onChange={value =>
                setDraftFilters(current => ({
                  ...current,
                  databaseId: value,
                }))
              }
            />

            <SelectField
              label="State"
              value={draftFilters.state}
              options={[
                { value: "all", label: "All sessions" },
                { value: "open", label: "Open sessions" },
                { value: "closed", label: "Closed sessions" },
              ]}
              onChange={value =>
                setDraftFilters(current => ({
                  ...current,
                  state: value as AdminDatabaseSessionState,
                }))
              }
            />

            <InputField
              label="Started from"
              type="datetime-local"
              value={draftFilters.startedFrom}
              onChange={value =>
                setDraftFilters(current => ({
                  ...current,
                  startedFrom: value,
                }))
              }
            />

            <InputField
              label="Started to"
              type="datetime-local"
              value={draftFilters.startedTo}
              onChange={value =>
                setDraftFilters(current => ({
                  ...current,
                  startedTo: value,
                }))
              }
            />

            <div className="flex flex-wrap items-end gap-3 lg:col-span-3">
              <Button type="submit">Apply filters</Button>
              <Button type="button" variant="outline" onClick={handleResetFilters}>
                Reset filters
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="gap-4">
          <div>
            <CardTitle>Recorded sessions</CardTitle>
            <CardDescription>
              Current results stay in the URL so refresh and back-forward navigation
              keep the same review context.
            </CardDescription>
          </div>

          <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
            <span className="text-sm text-stone-600">
              Showing {showingFrom}-{showingTo} of {sessionPage.totalCount}
            </span>

            <div className="flex flex-wrap items-center gap-3">
              <SelectField
                label="Page size"
                value={String(appliedParams.pageSize)}
                options={pageSizeOptions.map(value => ({
                  value: String(value),
                  label: String(value),
                }))}
                onChange={value =>
                  setSearchParams(
                    buildSearchParams({
                      filters: appliedFilters,
                      page: 1,
                      pageSize: Number(value),
                    })
                  )
                }
                compact
              />

              <div className="flex gap-2">
                <Button
                  variant="outline"
                  disabled={!canGoPrevious || isLoading}
                  onClick={() =>
                    setSearchParams(
                      buildSearchParams({
                        filters: appliedFilters,
                        page: sessionPage.page - 1,
                        pageSize: sessionPage.pageSize,
                      })
                    )
                  }
                >
                  Previous
                </Button>
                <Button
                  variant="outline"
                  disabled={!canGoNext || isLoading}
                  onClick={() =>
                    setSearchParams(
                      buildSearchParams({
                        filters: appliedFilters,
                        page: sessionPage.page + 1,
                        pageSize: sessionPage.pageSize,
                      })
                    )
                  }
                >
                  Next
                </Button>
              </div>
            </div>
          </div>
        </CardHeader>

        <CardContent>
          {isLoading ? (
            <div className="grid gap-3">
              {[0, 1, 2].map(index => (
                <Skeleton key={index} className="h-14 rounded-[1rem]" />
              ))}
            </div>
          ) : loadError ? (
            <div className="space-y-4">
              <Alert variant="destructive">
                <AlertTitle>Session review unavailable</AlertTitle>
                <AlertDescription>{loadError}</AlertDescription>
              </Alert>
              <Button variant="outline" onClick={handleRetry}>
                Retry
              </Button>
            </div>
          ) : sessionPage.totalCount === 0 ? (
            <Alert>
              <AlertTitle>
                {hasActiveFilters
                  ? "No sessions match the current filters"
                  : "No recorded sessions"}
              </AlertTitle>
              <AlertDescription>
                {hasActiveFilters
                  ? "Try widening the filters or reset them to review the full session history."
                  : "Successful proxy traffic has not been observed yet, so there are no session rows to review."}
              </AlertDescription>
            </Alert>
          ) : (
            <div className="overflow-x-auto">
              <table className="min-w-full text-left text-sm">
                <thead className="text-xs uppercase tracking-[0.18em] text-stone-500">
                  <tr>
                    <TableHead>User email</TableHead>
                    <TableHead>Team</TableHead>
                    <TableHead>Database</TableHead>
                    <TableHead>Client address</TableHead>
                    <TableHead>Started</TableHead>
                    <TableHead>Ended</TableHead>
                    <TableHead>Bytes sent</TableHead>
                    <TableHead>Bytes received</TableHead>
                  </tr>
                </thead>
                <tbody>
                  {sessionPage.items.map(session => (
                    <tr key={session.id} className="border-t border-stone-200/80">
                      <TableCell>{session.user.email}</TableCell>
                      <TableCell>{session.user.team.name}</TableCell>
                      <TableCell>{session.database.databaseName}</TableCell>
                      <TableCell>{session.clientAddr}</TableCell>
                      <TableCell>{session.startedAt}</TableCell>
                      <TableCell>{session.endedAt ?? "Open"}</TableCell>
                      <TableCell>
                        {session.bytesSent === null ? "n/a" : session.bytesSent}
                      </TableCell>
                      <TableCell>
                        {session.bytesReceived === null
                          ? "n/a"
                          : session.bytesReceived}
                      </TableCell>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </main>
  )
}

function SelectField({
  compact = false,
  label,
  onChange,
  options,
  value,
}: {
  compact?: boolean
  label: string
  value: string
  options: Array<{ value: string; label: string }>
  onChange: (value: string) => void
}) {
  return (
    <label
      className={
        compact
          ? "grid gap-2 text-sm font-medium text-stone-800"
          : "grid gap-2 text-sm font-medium text-stone-800"
      }
    >
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

function readAppliedParams(searchParams: URLSearchParams): AppliedSessionParams {
  return {
    page: readPositiveInt(searchParams.get("page"), 1),
    pageSize: readAllowedPageSize(searchParams.get("pageSize")),
    userId: searchParams.get("userId") || undefined,
    teamId: searchParams.get("teamId") || undefined,
    databaseId: searchParams.get("databaseId") || undefined,
    state: readState(searchParams.get("state")),
    startedFrom: searchParams.get("startedFrom") || undefined,
    startedTo: searchParams.get("startedTo") || undefined,
  }
}

function readPositiveInt(rawValue: string | null, defaultValue: number) {
  const parsedValue = Number(rawValue)

  if (!Number.isInteger(parsedValue) || parsedValue < 1) {
    return defaultValue
  }

  return parsedValue
}

function readAllowedPageSize(rawValue: string | null) {
  const parsedValue = Number(rawValue)

  if (!pageSizeOptions.includes(parsedValue)) {
    return 25
  }

  return parsedValue
}

function readState(rawValue: string | null): AdminDatabaseSessionState {
  if (rawValue === "open" || rawValue === "closed" || rawValue === "all") {
    return rawValue
  }

  return "all"
}

function filtersFromParams(params: ListAdminSessionsParams): SessionFiltersFormState {
  return {
    teamId: params.teamId ?? "",
    userId: params.userId ?? "",
    databaseId: params.databaseId ?? "",
    state: params.state ?? "all",
    startedFrom: params.startedFrom ? toDateTimeLocalValue(params.startedFrom) : "",
    startedTo: params.startedTo ? toDateTimeLocalValue(params.startedTo) : "",
  }
}

function sanitizeFilters(
  filters: SessionFiltersFormState,
  users: Awaited<ReturnType<typeof adminApi.listUsers>>
): SessionFiltersFormState {
  const selectedUser = users.find(user => user.id === filters.userId)
  const shouldClearUser =
    filters.teamId !== "" &&
    filters.userId !== "" &&
    selectedUser?.team.id !== filters.teamId

  return {
    ...filters,
    userId: shouldClearUser ? "" : filters.userId,
  }
}

function buildSearchParams({
  filters,
  page,
  pageSize,
}: {
  filters: SessionFiltersFormState
  page: number
  pageSize: number
}) {
  const nextSearchParams = new URLSearchParams()

  if (page > 1) {
    nextSearchParams.set("page", String(page))
  }

  if (pageSize !== 25) {
    nextSearchParams.set("pageSize", String(pageSize))
  }

  if (filters.teamId !== "") {
    nextSearchParams.set("teamId", filters.teamId)
  }

  if (filters.userId !== "") {
    nextSearchParams.set("userId", filters.userId)
  }

  if (filters.databaseId !== "") {
    nextSearchParams.set("databaseId", filters.databaseId)
  }

  if (filters.state !== "all") {
    nextSearchParams.set("state", filters.state)
  }

  if (filters.startedFrom !== "") {
    nextSearchParams.set("startedFrom", new Date(filters.startedFrom).toISOString())
  }

  if (filters.startedTo !== "") {
    nextSearchParams.set("startedTo", new Date(filters.startedTo).toISOString())
  }

  return nextSearchParams
}

function toDateTimeLocalValue(value: string) {
  const date = new Date(value)

  if (Number.isNaN(date.getTime())) {
    return ""
  }

  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours()
  )}:${pad(date.getMinutes())}`
}

function pad(value: number) {
  return String(value).padStart(2, "0")
}

function recoverOutOfRangePage(
  searchKey: string,
  params: AppliedSessionParams,
  sessionPage: AdminDatabaseSessionPageResponse,
  setSearchParams: ReturnType<typeof useSearchParams>[1]
) {
  if (
    params.page <= 1 ||
    sessionPage.totalCount === 0 ||
    sessionPage.items.length > 0
  ) {
    return false
  }

  const lastPage = Math.max(1, Math.ceil(sessionPage.totalCount / sessionPage.pageSize))

  if (params.page <= lastPage) {
    return false
  }

  const nextSearchParams = new URLSearchParams(searchKey)

  if (lastPage > 1) {
    nextSearchParams.set("page", String(lastPage))
  } else {
    nextSearchParams.delete("page")
  }

  setSearchParams(nextSearchParams)
  return true
}
