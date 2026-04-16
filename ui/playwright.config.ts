import { defineConfig, devices } from "@playwright/test"

const isCI = Boolean(process.env.CI)
const artifactsDir = process.env.PLAYWRIGHT_ARTIFACTS_DIR ?? "../.e2e-tmp/playwright"

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  retries: isCI ? 2 : 0,
  workers: 1,
  outputDir: `${artifactsDir}/test-results`,
  reporter: isCI
    ? [["github"], ["html", { open: "never", outputFolder: `${artifactsDir}/report` }]]
    : [["list"]],
  use: {
    ...devices["Desktop Chrome"],
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? "http://localhost:5173",
    headless: true,
    trace: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
})
