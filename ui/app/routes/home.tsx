import axios from "axios"
import { useCallback, useEffect, useMemo, useState } from "react"

import { accessApi, type IssuedOtp } from "~/api/accessApi"
import type { EffectiveDatabaseAccess } from "~/api/types"
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert"
import { Badge } from "~/components/ui/badge"
import { Button } from "~/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "~/components/ui/card"
import { Separator } from "~/components/ui/separator"
import { Skeleton } from "~/components/ui/skeleton"
import { useAuth } from "~/auth/AuthContext"
import { config } from "~/config"
import { ACCESS_SOURCE_META } from "~/lib/accessSource"

type OtpError = {
  kind: "forbidden" | "missing" | "retryable"
  title: string
  message: string
}

type OtpCardState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "success"; issuedOtp: IssuedOtp }
  | { status: "error"; error: OtpError }

export default function Home() {
  const { user } = useAuth()
  const [access, setAccess] = useState<EffectiveDatabaseAccess[]>([])
  const [isFetchingAccess, setIsFetchingAccess] = useState(false)
  const [hasLoadedAccess, setHasLoadedAccess] = useState(false)
  const [hasRequestedAccess, setHasRequestedAccess] = useState(false)
  const [routeError, setRouteError] = useState<string | null>(null)
  const [otpStates, setOtpStates] = useState<Record<string, OtpCardState>>({})

  const loadAccess = useCallback(async () => {
    setIsFetchingAccess(true)
    setRouteError(null)

    try {
      const nextAccess = await accessApi.listEffectiveAccess()
      const databaseIds = new Set(nextAccess.map(item => item.databaseId))

      setAccess(nextAccess)
      setOtpStates(current =>
        Object.fromEntries(
          Object.entries(current).filter(([databaseId]) => databaseIds.has(databaseId))
        )
      )
      setHasLoadedAccess(true)
    } catch (error) {
      console.error("Failed to load effective access", error)
      setRouteError(
        "Couldn't load your database access. Check the service status and try again."
      )
    } finally {
      setIsFetchingAccess(false)
    }
  }, [])

  useEffect(() => {
    if (!user || hasRequestedAccess || isFetchingAccess) {
      return
    }

    setHasRequestedAccess(true)
    void loadAccess()
  }, [hasRequestedAccess, isFetchingAccess, loadAccess, user])

  const fullName = useMemo(() => {
    if (!user) {
      return ""
    }

    const composedName = `${user.firstName} ${user.lastName}`.trim()
    return composedName || user.preferredUsername || user.email
  }, [user])

  if (!user || (!hasLoadedAccess && (!hasRequestedAccess || isFetchingAccess) && !routeError)) {
    return <LoadingDashboard />
  }

  if (routeError) {
    return <AccessErrorState onRetry={loadAccess} isRetrying={isFetchingAccess} />
  }

  if (!access.length) {
    return (
      <EmptyAccessState
        fullName={fullName}
        email={user.email}
        team={user.team}
        onRefresh={loadAccess}
        isRefreshing={isFetchingAccess}
      />
    )
  }

  return (
    <main className="grid gap-6">
      <div className="flex max-w-6xl flex-col gap-6">
        <DashboardIntro
          fullName={fullName}
          email={user.email}
          team={user.team}
          accessCount={access.length}
          onRefresh={loadAccess}
          isRefreshing={isFetchingAccess}
        />

        <section className="grid gap-5">
          {access.map(databaseAccess => {
            const otpState = otpStates[databaseAccess.databaseId] ?? { status: "idle" }

            return (
              <DatabaseAccessCard
                key={databaseAccess.databaseId}
                access={databaseAccess}
                otpState={otpState}
                onGenerateOtp={async () => {
                  setOtpStates(current => ({
                    ...current,
                    [databaseAccess.databaseId]: { status: "loading" },
                  }))

                  try {
                    const issuedOtp = await accessApi.issueOtp(databaseAccess.databaseId)
                    setOtpStates(current => ({
                      ...current,
                      [databaseAccess.databaseId]: {
                        status: "success",
                        issuedOtp,
                      },
                    }))
                  } catch (error) {
                    console.error("Failed to issue OTP", error)
                    setOtpStates(current => ({
                      ...current,
                      [databaseAccess.databaseId]: {
                        status: "error",
                        error: mapOtpError(error),
                      },
                    }))
                  }
                }}
                onRefreshAccess={loadAccess}
              />
            )
          })}
        </section>
      </div>
    </main>
  )
}

function DashboardIntro({
  fullName,
  email,
  team,
  accessCount,
  onRefresh,
  isRefreshing,
}: {
  fullName: string
  email: string
  team: string
  accessCount: number
  onRefresh: () => Promise<void>
  isRefreshing: boolean
}) {
  return (
    <section className="rounded-[2rem] border border-stone-200/80 bg-white/80 p-6 shadow-[0_28px_80px_-48px_rgba(28,25,23,0.55)] backdrop-blur sm:p-8">
      <div className="flex flex-col gap-6 lg:flex-row lg:items-end lg:justify-between">
        <div className="max-w-3xl">
          <p className="text-xs font-semibold uppercase tracking-[0.36em] text-stone-500">
            DB Watchdog
          </p>
          <h1 className="mt-3 text-3xl font-semibold tracking-tight text-stone-950 sm:text-4xl">
            Manual database access
          </h1>
          <p className="mt-3 max-w-2xl text-sm leading-7 text-stone-600 sm:text-base">
            Generate a short-lived OTP for each database you can reach, then use the
            proxy connection details shown beneath the card for manual login.
          </p>
        </div>

        <Card className="w-full max-w-md border-stone-900/10 bg-stone-950 text-stone-50">
          <CardHeader className="gap-2 pb-4">
            <CardTitle className="text-xl text-stone-50">{fullName}</CardTitle>
            <CardDescription className="text-stone-300">
              Active user context for proxy access.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3 pb-5">
            <IdentityRow label="Email" value={email} muted />
            <IdentityRow label="Team" value={team} muted />
            <IdentityRow label="Accessible databases" value={String(accessCount)} muted />
          </CardContent>
          <CardFooter className="pt-0">
            <Button
              variant="outline"
              className="border-stone-700 bg-stone-900 text-stone-50 hover:bg-stone-800"
              onClick={() => void onRefresh()}
              disabled={isRefreshing}
            >
              {isRefreshing ? "Refreshing..." : "Refresh access"}
            </Button>
          </CardFooter>
        </Card>
      </div>
    </section>
  )
}

function EmptyAccessState({
  fullName,
  email,
  team,
  onRefresh,
  isRefreshing,
}: {
  fullName: string
  email: string
  team: string
  onRefresh: () => Promise<void>
  isRefreshing: boolean
}) {
  return (
    <main className="grid gap-6">
      <div className="flex max-w-4xl flex-col gap-6">
        <DashboardIntro
          fullName={fullName}
          email={email}
          team={team}
          accessCount={0}
          onRefresh={onRefresh}
          isRefreshing={isRefreshing}
        />

        <Card>
          <CardHeader>
            <CardTitle>No databases available</CardTitle>
            <CardDescription>
              Your account is authenticated, but there are no active database grants or
              extensions for your current team context.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Alert>
              <AlertTitle>No access has been assigned yet</AlertTitle>
              <AlertDescription>
                Contact a DBA if you expected access. When a team grant or user
                extension is added, it will appear here automatically after refresh.
              </AlertDescription>
            </Alert>
          </CardContent>
        </Card>
      </div>
    </main>
  )
}

function AccessErrorState({
  onRetry,
  isRetrying,
}: {
  onRetry: () => Promise<void>
  isRetrying: boolean
}) {
  return (
    <main className="grid gap-6">
      <div className="flex max-w-3xl flex-col gap-6">
        <Card>
          <CardHeader>
            <CardTitle>Database access is temporarily unavailable</CardTitle>
            <CardDescription>
              The dashboard could not retrieve your effective access list.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <Alert variant="destructive">
              <AlertTitle>Couldn&apos;t load your database access</AlertTitle>
              <AlertDescription>
                The backend returned an unexpected error or could not be reached. Retry
                when the service is available again.
              </AlertDescription>
            </Alert>
            <Button onClick={() => void onRetry()} disabled={isRetrying}>
              {isRetrying ? "Retrying..." : "Retry"}
            </Button>
          </CardContent>
        </Card>
      </div>
    </main>
  )
}

function LoadingDashboard() {
  return (
    <main className="grid gap-6">
      <div className="flex max-w-6xl flex-col gap-6">
        <Card className="overflow-hidden">
          <CardContent className="space-y-5 p-6 sm:p-8">
            <div className="space-y-3">
              <Skeleton className="h-3 w-32" />
              <Skeleton className="h-12 w-72" />
              <Skeleton className="h-5 w-full max-w-2xl" />
            </div>
            <div className="grid gap-4 md:grid-cols-3">
              <Skeleton className="h-24 rounded-[1.5rem]" />
              <Skeleton className="h-24 rounded-[1.5rem]" />
              <Skeleton className="h-24 rounded-[1.5rem]" />
            </div>
          </CardContent>
        </Card>

        {[0, 1].map(index => (
          <Card key={index}>
            <CardHeader>
              <Skeleton className="h-8 w-64" />
              <Skeleton className="h-5 w-80" />
            </CardHeader>
            <CardContent className="grid gap-4 md:grid-cols-2">
              <Skeleton className="h-20 rounded-[1.5rem]" />
              <Skeleton className="h-20 rounded-[1.5rem]" />
              <Skeleton className="h-20 rounded-[1.5rem]" />
              <Skeleton className="h-20 rounded-[1.5rem]" />
            </CardContent>
            <CardFooter>
              <Skeleton className="h-10 w-36 rounded-full" />
            </CardFooter>
          </Card>
        ))}

        <p className="text-sm font-medium text-stone-600">Loading your access...</p>
      </div>
    </main>
  )
}

function DatabaseAccessCard({
  access,
  otpState,
  onGenerateOtp,
  onRefreshAccess,
}: {
  access: EffectiveDatabaseAccess
  otpState: OtpCardState
  onGenerateOtp: () => Promise<void>
  onRefreshAccess: () => Promise<void>
}) {
  const accessSourceMeta = ACCESS_SOURCE_META[access.accessSource] ?? {
    label: access.accessSource,
    variant: "outline" as const,
  }

  return (
    <Card data-testid={`database-access-card-${access.databaseId}`}>
      <CardHeader className="gap-4">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
          <div>
            <CardTitle className="text-2xl">{access.databaseName}</CardTitle>
            <CardDescription className="mt-2">
              Reach the registered backend through the proxy using your login identifier
              and a generated OTP.
            </CardDescription>
          </div>
          <Badge variant={accessSourceMeta.variant}>{accessSourceMeta.label}</Badge>
        </div>
      </CardHeader>

      <CardContent className="space-y-5">
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <DetailTile label="Engine" value={access.engine} />
          <DetailTile label="Host" value={access.host} />
          <DetailTile label="Port" value={String(access.port)} />
          <DetailTile label="Login identifier" value={access.loginIdentifier} />
          <DetailTile label="Access source" value={accessSourceMeta.label} />
          {access.extensionExpiresAt ? (
            <DetailTile label="Extension expires at" value={access.extensionExpiresAt} />
          ) : null}
        </div>

        {(otpState.status === "success" || otpState.status === "error") && (
          <>
            <Separator />
            {otpState.status === "success" ? (
              <OtpDetails issuedOtp={otpState.issuedOtp} loginIdentifier={access.loginIdentifier} />
            ) : (
              <OtpErrorPanel
                error={otpState.error}
                onRetry={onGenerateOtp}
                onRefreshAccess={onRefreshAccess}
              />
            )}
          </>
        )}
      </CardContent>

      <CardFooter className="justify-end">
        <Button onClick={() => void onGenerateOtp()} disabled={otpState.status === "loading"}>
          {otpState.status === "loading" ? "Generating..." : "Generate OTP"}
        </Button>
      </CardFooter>
    </Card>
  )
}

function OtpDetails({
  issuedOtp,
  loginIdentifier,
}: {
  issuedOtp: IssuedOtp
  loginIdentifier: string
}) {
  return (
    <div className="grid gap-4 lg:grid-cols-[1.15fr_0.85fr]">
      <div className="rounded-[1.5rem] border border-teal-200 bg-teal-50 p-5">
        <p className="text-xs font-semibold uppercase tracking-[0.28em] text-teal-800">
          One-time password
        </p>
        <p
          className="mt-3 break-all font-mono text-2xl font-semibold text-stone-950"
          data-testid={`database-otp-value-${issuedOtp.database.id}`}
        >
          {issuedOtp.otp}
        </p>
        <p className="mt-3 text-sm text-stone-700">
          Expires at <span className="font-medium">{issuedOtp.expiresAt}</span>
        </p>
      </div>

      <div className="rounded-[1.5rem] border border-stone-200 bg-stone-950 p-5 text-stone-50">
        <p className="text-xs font-semibold uppercase tracking-[0.28em] text-stone-300">
          Proxy connection
        </p>
        <dl className="mt-4 grid gap-3 text-sm">
          <ConnectionRow label="host" value={config.proxy.host} />
          <ConnectionRow label="port" value={String(config.proxy.port)} />
          <ConnectionRow label="user" value={loginIdentifier} />
          <ConnectionRow label="database" value={issuedOtp.database.databaseName} />
        </dl>
      </div>

      <Alert className="lg:col-span-2">
        <AlertTitle>OTP visibility is intentionally short-lived</AlertTitle>
        <AlertDescription>
          This OTP is shown once in the dashboard and expires quickly. If you generate a
          new one for the same database, it replaces the previous value immediately.
        </AlertDescription>
      </Alert>
    </div>
  )
}

function OtpErrorPanel({
  error,
  onRetry,
  onRefreshAccess,
}: {
  error: OtpError
  onRetry: () => Promise<void>
  onRefreshAccess: () => Promise<void>
}) {
  return (
    <Alert variant={error.kind === "forbidden" ? "default" : "destructive"}>
      <AlertTitle>{error.title}</AlertTitle>
      <AlertDescription className="space-y-3">
        <p>{error.message}</p>
        {error.kind === "retryable" ? (
          <Button variant="outline" size="sm" onClick={() => void onRetry()}>
            Retry OTP request
          </Button>
        ) : null}
        {error.kind === "missing" ? (
          <Button variant="outline" size="sm" onClick={() => void onRefreshAccess()}>
            Refresh access list
          </Button>
        ) : null}
      </AlertDescription>
    </Alert>
  )
}

function DetailTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-[1.5rem] border border-stone-200/80 bg-stone-50/80 p-4">
      <p className="text-xs font-semibold uppercase tracking-[0.2em] text-stone-500">
        {label}
      </p>
      <p className="mt-2 break-all text-sm font-medium text-stone-950">{value}</p>
    </div>
  )
}

function IdentityRow({
  label,
  value,
  muted = false,
}: {
  label: string
  value: string
  muted?: boolean
}) {
  return (
    <div className="flex items-center justify-between gap-4">
      <span className={muted ? "text-sm text-stone-400" : "text-sm text-stone-500"}>
        {label}
      </span>
      <span className="text-right text-sm font-medium">{value}</span>
    </div>
  )
}

function ConnectionRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="grid gap-1 sm:grid-cols-[90px_1fr] sm:items-baseline">
      <dt className="font-mono text-xs uppercase tracking-[0.24em] text-stone-400">
        {label}
      </dt>
      <dd className="break-all font-mono text-sm text-stone-50">{value}</dd>
    </div>
  )
}

function mapOtpError(error: unknown): OtpError {
  const status = axios.isAxiosError(error) ? error.response?.status : undefined

  if (status === 401) {
    return {
      kind: "retryable",
      title: "Session expired",
      message: "Your session has expired. You will need to sign in again.",
    }
  }

  if (status === 403) {
    return {
      kind: "forbidden",
      title: "OTP issuance is no longer allowed",
      message:
        "Your current access does not allow OTP generation for this database anymore.",
    }
  }

  if (status === 404) {
    return {
      kind: "missing",
      title: "Database no longer available",
      message:
        "The database registration changed or was removed. Refresh your access list to continue.",
    }
  }

  return {
    kind: "retryable",
    title: "Couldn't generate an OTP",
    message:
      "A temporary server or network issue interrupted OTP issuance. Retry the request.",
  }
}
