import axios from "axios"
import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from "react"

import {
  adminApi,
  type CreateDatabaseInput,
  type UpdateDatabaseInput,
} from "~/api/adminApi"
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

type DatabaseFormState = {
  engine: string
  host: string
  port: string
  technicalUser: string
  technicalPassword: string
  databaseName: string
}

type FormMode =
  | { kind: "create" }
  | {
      kind: "edit"
      databaseId: string
    }

const initialFormState: DatabaseFormState = {
  engine: "postgres",
  host: "",
  port: "5432",
  technicalUser: "",
  technicalPassword: "",
  databaseName: "",
}

export default function AdminDatabasesPage() {
  const [databases, setDatabases] = useState<Awaited<
    ReturnType<typeof adminApi.listDatabases>
  >>([])
  const [form, setForm] = useState<DatabaseFormState>(initialFormState)
  const [formMode, setFormMode] = useState<FormMode>({ kind: "create" })
  const [isLoading, setIsLoading] = useState(true)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [activeMutationId, setActiveMutationId] = useState<string | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [submitSuccess, setSubmitSuccess] = useState<string | null>(null)

  const loadDatabases = useCallback(async () => {
    setLoadError(null)

    try {
      const nextDatabases = await adminApi.listDatabases()
      setDatabases(nextDatabases)
    } catch (error) {
      console.error("Failed to load admin databases", error)
      setLoadError(
        "The database registry could not be loaded. Check the backend and retry."
      )
    } finally {
      setIsLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadDatabases()
  }, [loadDatabases])

  useEffect(() => {
    if (formMode.kind !== "edit") {
      return
    }

    const selectedDatabase = databases.find(database => database.id === formMode.databaseId)
    if (!selectedDatabase) {
      setFormMode({ kind: "create" })
      setForm(initialFormState)
      return
    }

    setForm(toFormState(selectedDatabase))
  }, [databases, formMode])

  const selectedDatabase =
    formMode.kind === "edit"
      ? databases.find(database => database.id === formMode.databaseId) ?? null
      : null

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setIsSubmitting(true)
    setSubmitError(null)
    setSubmitSuccess(null)

    try {
      if (formMode.kind === "create") {
        const payload: CreateDatabaseInput = {
          engine: form.engine,
          host: form.host.trim(),
          port: Number(form.port),
          technicalUser: form.technicalUser.trim(),
          technicalPassword: form.technicalPassword,
          databaseName: form.databaseName.trim(),
        }

        await adminApi.createDatabase(payload)
        setForm(initialFormState)
        setSubmitSuccess("Database registered successfully.")
      } else {
        const payload: UpdateDatabaseInput = {
          engine: form.engine,
          host: form.host.trim(),
          port: Number(form.port),
          technicalUser: form.technicalUser.trim(),
          technicalPassword: form.technicalPassword === "" ? null : form.technicalPassword,
          databaseName: form.databaseName.trim(),
        }

        await adminApi.updateDatabase(formMode.databaseId, payload)
        setSubmitSuccess("Database updated successfully.")
      }

      await loadDatabases()
    } catch (error) {
      console.error("Failed to save database", error)
      setSubmitError(mapDatabaseError(error))
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleEdit = (databaseId: string) => {
    setSubmitError(null)
    setSubmitSuccess(null)
    setFormMode({ kind: "edit", databaseId })
  }

  const handleResetForm = () => {
    setSubmitError(null)
    setSubmitSuccess(null)
    setFormMode({ kind: "create" })
    setForm(initialFormState)
  }

  const handleStatusChange = async (
    database: Awaited<ReturnType<typeof adminApi.listDatabases>>[number]
  ) => {
    const actionLabel = database.isActive ? "deactivate" : "reactivate"
    const confirmed = window.confirm(
      database.isActive
        ? `Deactivate ${database.databaseName}? Existing grants remain, but new OTPs and effective access will stop immediately.`
        : `Reactivate ${database.databaseName}? Existing grants and extensions will become effective again.`
    )

    if (!confirmed) {
      return
    }

    setActiveMutationId(database.id)
    setSubmitError(null)
    setSubmitSuccess(null)

    try {
      if (database.isActive) {
        await adminApi.deactivateDatabase(database.id)
        setSubmitSuccess("Database deactivated successfully.")
      } else {
        await adminApi.reactivateDatabase(database.id)
        setSubmitSuccess("Database reactivated successfully.")
      }

      await loadDatabases()
    } catch (error) {
      console.error(`Failed to ${actionLabel} database`, error)
      setSubmitError(mapDatabaseError(error))
    } finally {
      setActiveMutationId(null)
    }
  }

  return (
    <main className="grid gap-6">
      <Card>
        <CardHeader>
          <CardTitle>Admin Databases</CardTitle>
          <CardDescription>
            Register, update, deactivate, and reactivate database targets that the
            reverse proxy can reach through stored technical credentials.
          </CardDescription>
        </CardHeader>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>
            {formMode.kind === "edit" && selectedDatabase
              ? `Edit ${selectedDatabase.databaseName}`
              : "Register database"}
          </CardTitle>
          <CardDescription>
            {formMode.kind === "edit"
              ? "Leave Technical password blank to keep the current stored password."
              : "Create a database entry that can later receive grants, extensions, and OTP traffic."}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {submitSuccess ? (
            <Alert className="border-teal-200 bg-teal-50 text-teal-950">
              <AlertTitle>Database request completed</AlertTitle>
              <AlertDescription>{submitSuccess}</AlertDescription>
            </Alert>
          ) : null}

          {submitError ? (
            <Alert variant="destructive">
              <AlertTitle>Database request failed</AlertTitle>
              <AlertDescription>{submitError}</AlertDescription>
            </Alert>
          ) : null}

          <form className="grid gap-4 lg:grid-cols-2" onSubmit={handleSubmit}>
            <Field
              label="Engine"
              value={form.engine}
              onChange={value => setForm(current => ({ ...current, engine: value }))}
            />
            <Field
              label="Host"
              value={form.host}
              onChange={value => setForm(current => ({ ...current, host: value }))}
            />
            <Field
              label="Port"
              type="number"
              value={form.port}
              onChange={value => setForm(current => ({ ...current, port: value }))}
            />
            <Field
              label="Technical user"
              value={form.technicalUser}
              onChange={value =>
                setForm(current => ({ ...current, technicalUser: value }))
              }
            />
            <Field
              label="Technical password"
              type="password"
              value={form.technicalPassword}
              onChange={value =>
                setForm(current => ({ ...current, technicalPassword: value }))
              }
            />
            <Field
              label="Database name"
              value={form.databaseName}
              onChange={value =>
                setForm(current => ({ ...current, databaseName: value }))
              }
            />

            <div className="flex flex-wrap gap-3 lg:col-span-2">
              <Button type="submit" disabled={isSubmitting}>
                {isSubmitting
                  ? formMode.kind === "edit"
                    ? "Saving..."
                    : "Creating..."
                  : formMode.kind === "edit"
                    ? "Save changes"
                    : "Create database"}
              </Button>
              {formMode.kind === "edit" ? (
                <Button type="button" variant="outline" onClick={handleResetForm}>
                  Cancel edit
                </Button>
              ) : null}
            </div>
          </form>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Registered databases</CardTitle>
          <CardDescription>
            Review the current registry backing proxy routing, access management, and
            OTP availability.
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
                <AlertTitle>Database list unavailable</AlertTitle>
                <AlertDescription>{loadError}</AlertDescription>
              </Alert>
              <Button variant="outline" onClick={() => void loadDatabases()}>
                Retry
              </Button>
            </div>
          ) : !databases.length ? (
            <Alert>
              <AlertTitle>No databases registered yet</AlertTitle>
              <AlertDescription>
                Create the first database entry above to make it available for grants
                and OTP flows.
              </AlertDescription>
            </Alert>
          ) : (
            <div className="overflow-x-auto">
              <table className="min-w-full text-left text-sm">
                <thead className="text-xs uppercase tracking-[0.18em] text-stone-500">
                  <tr>
                    <TableHead>Database</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>Engine</TableHead>
                    <TableHead>Host</TableHead>
                    <TableHead>Port</TableHead>
                    <TableHead>Technical user</TableHead>
                    <TableHead>Updated</TableHead>
                    <TableHead>Action</TableHead>
                  </tr>
                </thead>
                <tbody>
                  {databases.map(database => {
                    const isMutating = activeMutationId === database.id

                    return (
                      <tr key={database.id} className="border-t border-stone-200/80">
                        <TableCell>{database.databaseName}</TableCell>
                        <TableCell>
                          <Badge variant={database.isActive ? "secondary" : "outline"}>
                            {database.isActive ? "Active" : "Inactive"}
                          </Badge>
                        </TableCell>
                        <TableCell>{database.engine}</TableCell>
                        <TableCell>{database.host}</TableCell>
                        <TableCell>{database.port}</TableCell>
                        <TableCell>{database.technicalUser}</TableCell>
                        <TableCell>{database.updatedAt}</TableCell>
                        <TableCell>
                          <div className="flex flex-wrap gap-2">
                            <Button
                              variant="outline"
                              size="sm"
                              disabled={isSubmitting || isMutating}
                              onClick={() => handleEdit(database.id)}
                            >
                              Edit
                            </Button>
                            <Button
                              variant={database.isActive ? "destructive" : "outline"}
                              size="sm"
                              disabled={isSubmitting || isMutating}
                              onClick={() => void handleStatusChange(database)}
                            >
                              {isMutating
                                ? database.isActive
                                  ? "Deactivating..."
                                  : "Reactivating..."
                                : database.isActive
                                  ? "Deactivate"
                                  : "Reactivate"}
                            </Button>
                          </div>
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
    </main>
  )
}

function Field({
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

function toFormState(
  database: Awaited<ReturnType<typeof adminApi.listDatabases>>[number]
): DatabaseFormState {
  return {
    engine: database.engine,
    host: database.host,
    port: String(database.port),
    technicalUser: database.technicalUser,
    technicalPassword: "",
    databaseName: database.databaseName,
  }
}

function mapDatabaseError(error: unknown): string {
  if (axios.isAxiosError(error) && typeof error.response?.data === "string") {
    return error.response.data
  }

  return "The backend rejected the database request or could not be reached."
}
