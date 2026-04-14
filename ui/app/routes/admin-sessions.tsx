import { useCallback, useEffect, useState, type ReactNode } from "react"

import { adminApi } from "~/api/adminApi"
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

export default function AdminSessionsPage() {
  const [sessions, setSessions] = useState<Awaited<
    ReturnType<typeof adminApi.listSessions>
  >>([])
  const [isLoading, setIsLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const loadSessions = useCallback(async () => {
    setLoadError(null)

    try {
      const nextSessions = await adminApi.listSessions()
      setSessions(nextSessions)
    } catch (error) {
      console.error("Failed to load sessions", error)
      setLoadError("Recorded proxy sessions could not be loaded.")
    } finally {
      setIsLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadSessions()
  }, [loadSessions])

  return (
    <main className="grid gap-6">
      <Card>
        <CardHeader>
          <CardTitle>Admin Sessions</CardTitle>
          <CardDescription>
            Review the recorded proxy sessions in backend-provided order.
          </CardDescription>
        </CardHeader>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Recorded sessions</CardTitle>
          <CardDescription>
            Filtering and pagination remain deferred in this slice.
          </CardDescription>
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
              <Button variant="outline" onClick={() => void loadSessions()}>
                Retry
              </Button>
            </div>
          ) : !sessions.length ? (
            <Alert>
              <AlertTitle>No recorded sessions</AlertTitle>
              <AlertDescription>
                Successful proxy traffic has not been observed yet, so there are no
                session rows to review.
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
                  {sessions.map(session => (
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

function TableHead({ children }: { children: ReactNode }) {
  return <th className="px-3 py-3 font-semibold">{children}</th>
}

function TableCell({ children }: { children: ReactNode }) {
  return <td className="px-3 py-3 align-top text-stone-700">{children}</td>
}
