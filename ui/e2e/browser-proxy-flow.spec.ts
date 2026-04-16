import { expect, test, type Locator, type Page } from "@playwright/test"

import { loginViaKeycloak } from "./support/auth"
import { assertProxyQuerySucceeds } from "./support/proxy"
import { ADMIN_USER, REGULAR_USER, TEST_DATABASE, TEST_TEAM } from "./support/testData"

test("validates the browser + proxy end-to-end flow", async ({ browser, baseURL }) => {
  test.setTimeout(120_000)

  const adminContext = await browser.newContext()
  const userContext = await browser.newContext()
  const adminPage = await adminContext.newPage()
  const userPage = await userContext.newPage()

  try {
    await loginViaKeycloak(adminPage, ADMIN_USER, `${baseURL}/admin/databases`)
    await expect(
      adminPage.getByRole("heading", { name: "Admin Databases" })
    ).toBeVisible()

    await upsertDatabase(adminPage)
    await ensureEngineeringGrant(adminPage)

    await loginViaKeycloak(userPage, REGULAR_USER, `${baseURL}/`)
    const accessCard = await expectAccessCard(userPage, TEST_DATABASE.databaseName)

    await accessCard.getByRole("button", { name: "Generate OTP" }).click()

    const otpValue = await readOtpValue(accessCard)
    const connection = {
      host: await readConnectionValue(accessCard, "host"),
      port: await readConnectionValue(accessCard, "port"),
      user: await readConnectionValue(accessCard, "user"),
      database: await readConnectionValue(accessCard, "database"),
    }

    expect(connection.database).toBe(TEST_DATABASE.databaseName)
    await assertProxyQuerySucceeds({
      ...connection,
      otp: otpValue,
    })

    await adminPage.goto(`${baseURL}/admin/sessions`)
    await expect(
      adminPage.getByRole("heading", { name: "Admin Sessions" })
    ).toBeVisible()

    await expect
      .poll(
        async () => {
          await adminPage.reload()
          await adminPage.waitForLoadState("networkidle")
          return (await adminPage
            .locator("tbody tr")
            .filter({ hasText: REGULAR_USER.email })
            .filter({ hasText: TEST_TEAM.name })
            .filter({ hasText: TEST_DATABASE.databaseName })
            .count()) > 0
        },
        {
          timeout: 20_000,
          message: "Expected a recorded proxy session for the browser-issued OTP",
        }
      )
      .toBe(true)
  } finally {
    await Promise.allSettled([adminContext.close(), userContext.close()])
  }
})

async function upsertDatabase(page: Page): Promise<void> {
  await page.waitForLoadState("networkidle")
  const row = page.locator("tbody tr").filter({ hasText: TEST_DATABASE.databaseName }).first()

  if (await row.count()) {
    await row.getByRole("button", { name: "Edit" }).click()
  }

  await page.getByLabel("Engine").fill(TEST_DATABASE.engine)
  await page.getByLabel("Host").fill(TEST_DATABASE.host)
  await page.getByLabel("Port").fill(TEST_DATABASE.port)
  await page.getByLabel("Technical user").fill(TEST_DATABASE.technicalUser)
  await page.getByLabel("Technical password").fill(TEST_DATABASE.technicalPassword)
  await page.getByLabel("Database name").fill(TEST_DATABASE.databaseName)

  if (await row.count()) {
    await page.getByRole("button", { name: "Save changes" }).click()
    await expect(page.getByText("Database updated successfully.")).toBeVisible()
  } else {
    await page.getByRole("button", { name: "Create database" }).click()
    await expect(page.getByText("Database registered successfully.")).toBeVisible()
  }

  await page.reload()
  await page.waitForLoadState("networkidle")

  const updatedRow = page.locator("tbody tr").filter({ hasText: TEST_DATABASE.databaseName }).first()
  await expect(updatedRow).toBeVisible()

  const rowText = await updatedRow.textContent()
  if (rowText?.includes("Inactive")) {
    await updatedRow.getByRole("button", { name: "Reactivate" }).click()
    await expect(page.getByText("Database reactivated successfully.")).toBeVisible()
  }
}

async function ensureEngineeringGrant(page: Page): Promise<void> {
  await page.goto("/admin/access")
  await expect(page.getByRole("heading", { name: "Admin Access" })).toBeVisible()
  await page.waitForLoadState("networkidle")

  await page.getByLabel("Team").selectOption({ label: TEST_TEAM.name })
  await page.getByLabel("Grant database").selectOption({ label: TEST_DATABASE.databaseName })
  await page.getByRole("button", { name: "Add team grant" }).click()

  await expect(page.getByText("Team grant saved.")).toBeVisible()
}

async function expectAccessCard(page: Page, databaseName: string): Promise<Locator> {
  const databaseHeading = page.getByRole("heading", { name: databaseName })
  await expect(databaseHeading).toBeVisible({ timeout: 20_000 })

  return databaseHeading.locator(
    'xpath=ancestor::*[starts-with(@data-testid, "database-access-card-")][1]'
  )
}

async function readOtpValue(card: Locator): Promise<string> {
  const otpLocator = card.locator('[data-testid^="database-otp-value-"]').first()
  await expect(otpLocator).toBeVisible({ timeout: 20_000 })
  return (await otpLocator.textContent())?.trim() ?? ""
}

async function readConnectionValue(card: Locator, label: string): Promise<string> {
  const value = card
    .locator("dt", { hasText: new RegExp(`^${label}$`) })
    .locator("xpath=following-sibling::dd[1]")
  await expect(value).toBeVisible()
  return (await value.textContent())?.trim() ?? ""
}
