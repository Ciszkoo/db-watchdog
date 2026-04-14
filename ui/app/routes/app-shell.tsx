import { NavLink, Outlet, Link } from "react-router"
import { useMemo } from "react"

import { useAuth } from "~/auth/AuthContext"
import { Badge } from "~/components/ui/badge"
import { Button, buttonVariants } from "~/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "~/components/ui/card"
import { Skeleton } from "~/components/ui/skeleton"
import { cn } from "~/lib/utils"

const navigationItems = [
  { to: "/", label: "My Access", dbaOnly: false },
  { to: "/admin/databases", label: "Admin Databases", dbaOnly: true },
  { to: "/admin/access", label: "Admin Access", dbaOnly: true },
  { to: "/admin/sessions", label: "Admin Sessions", dbaOnly: true },
]

export default function AppShell() {
  const { authError, isAuthenticated, isDba, isLoading, login, logout, user } =
    useAuth()

  const fullName = useMemo(() => {
    if (!user) {
      return ""
    }

    const composedName = `${user.firstName} ${user.lastName}`.trim()
    return composedName || user.preferredUsername || user.email
  }, [user])

  if (authError) {
    return <AuthenticationErrorState message={authError} onLogin={login} />
  }

  if (isLoading || !isAuthenticated || !user) {
    return <ShellLoadingState />
  }

  return (
    <div className="min-h-screen px-4 py-6 sm:px-6 lg:px-10">
      <div className="mx-auto flex max-w-7xl flex-col gap-6">
        <header className="rounded-[2rem] border border-stone-200/80 bg-white/85 p-6 shadow-[0_28px_80px_-48px_rgba(28,25,23,0.55)] backdrop-blur sm:p-8">
          <div className="flex flex-col gap-6 xl:flex-row xl:items-end xl:justify-between">
            <div className="max-w-3xl">
              <p className="text-xs font-semibold uppercase tracking-[0.36em] text-stone-500">
                DB Watchdog
              </p>
              <h1 className="mt-3 text-3xl font-semibold tracking-tight text-stone-950 sm:text-4xl">
                Database access control
              </h1>
              <p className="mt-3 max-w-2xl text-sm leading-7 text-stone-600 sm:text-base">
                Review granted access, generate short-lived OTPs, and manage
                registered databases and policies when your role includes DBA.
              </p>
            </div>

            <div className="flex w-full max-w-2xl flex-col gap-4">
              <nav className="flex flex-wrap gap-2">
                {navigationItems
                  .filter(item => !item.dbaOnly || isDba)
                  .map(item => (
                    <NavLink
                      key={item.to}
                      to={item.to}
                      end={item.to === "/"}
                      className={({ isActive }) =>
                        cn(
                          buttonVariants({ variant: "ghost" }),
                          "rounded-full px-4",
                          isActive
                            ? "bg-stone-950 text-stone-50 hover:bg-stone-900 hover:text-stone-50"
                            : "bg-white/70 text-stone-700 hover:bg-stone-200/70 hover:text-stone-950"
                        )
                      }
                    >
                      {item.label}
                    </NavLink>
                  ))}
              </nav>

              <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div className="rounded-[1.5rem] border border-stone-200 bg-stone-950 px-5 py-4 text-stone-50">
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="text-base font-semibold">{fullName}</p>
                    {isDba ? <Badge variant="default">DBA</Badge> : null}
                  </div>
                  <p className="mt-1 text-sm text-stone-300">
                    {user.email} · {user.team}
                  </p>
                </div>

                <Button variant="outline" onClick={logout}>
                  Sign out
                </Button>
              </div>
            </div>
          </div>
        </header>

        <Outlet />
      </div>
    </div>
  )
}

function AuthenticationErrorState({
  message,
  onLogin,
}: {
  message: string
  onLogin: () => Promise<void>
}) {
  return (
    <main className="min-h-screen px-4 py-8 sm:px-6 lg:px-10">
      <div className="mx-auto flex max-w-3xl flex-col gap-6">
        <Card>
          <CardHeader>
            <CardTitle>Authentication is required</CardTitle>
            <CardDescription>
              The application couldn&apos;t finish the authentication bootstrap.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div
              role="alert"
              className="rounded-[1.25rem] border border-red-200 bg-red-50 px-4 py-4 text-sm text-red-950"
            >
              <p className="font-semibold">Sign-in flow needs to be restarted</p>
              <p className="mt-1 leading-6">{message}</p>
            </div>
            <Button onClick={() => void onLogin()}>Sign in again</Button>
          </CardContent>
        </Card>
      </div>
    </main>
  )
}

function ShellLoadingState() {
  return (
    <main className="min-h-screen px-4 py-8 sm:px-6 lg:px-10">
      <div className="mx-auto flex max-w-7xl flex-col gap-6">
        <Card className="overflow-hidden">
          <CardContent className="space-y-5 p-6 sm:p-8">
            <div className="space-y-3">
              <Skeleton className="h-3 w-32" />
              <Skeleton className="h-12 w-80" />
              <Skeleton className="h-5 w-full max-w-3xl" />
            </div>
            <div className="flex flex-wrap gap-3">
              <Skeleton className="h-10 w-36 rounded-full" />
              <Skeleton className="h-10 w-40 rounded-full" />
              <Skeleton className="h-10 w-40 rounded-full" />
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Loading your workspace</CardTitle>
            <CardDescription>
              Initializing authentication and restoring your access context.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4 md:grid-cols-2">
            <Skeleton className="h-28 rounded-[1.5rem]" />
            <Skeleton className="h-28 rounded-[1.5rem]" />
          </CardContent>
        </Card>
      </div>
    </main>
  )
}

export function AccessDeniedCard() {
  return (
    <main className="grid gap-6">
      <Card>
        <CardHeader>
          <CardTitle>Access denied</CardTitle>
          <CardDescription>
            Your current Keycloak roles do not include DBA, so admin routes are
            visible but unavailable.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div
            role="alert"
            className="rounded-[1.25rem] border border-red-200 bg-red-50 px-4 py-4 text-sm text-red-950"
          >
            <p className="font-semibold">Admin access requires the DBA realm role</p>
            <p className="mt-1 leading-6">
              Return to your own access dashboard or ask an administrator to update
              your Keycloak role assignments.
            </p>
          </div>

          <Link to="/" className={cn(buttonVariants({ variant: "outline" }))}>
            Back to My Access
          </Link>
        </CardContent>
      </Card>
    </main>
  )
}
