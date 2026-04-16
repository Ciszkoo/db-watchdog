import { execFile } from "node:child_process"
import { promisify } from "node:util"

const execFileAsync = promisify(execFile)

export async function assertProxyQuerySucceeds(input: {
  host: string
  port: string
  database: string
  user: string
  otp: string
}): Promise<string> {
  const { stdout } = await execFileAsync(
    "psql",
    [
      "--host",
      input.host,
      "--port",
      input.port,
      "--username",
      input.user,
      "--dbname",
      input.database,
      "--tuples-only",
      "--no-align",
      "--command",
      "SELECT 1",
    ],
    {
      env: {
        ...process.env,
        PGPASSWORD: input.otp,
        PGSSLMODE: "require",
      },
      timeout: 20_000,
    }
  )

  const result = stdout.trim()
  if (result !== "1") {
    throw new Error(`Expected proxy query result to be 1, received "${result}"`)
  }

  return result
}
