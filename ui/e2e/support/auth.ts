import { expect, type Page } from "@playwright/test"

export async function loginViaKeycloak(
  page: Page,
  credentials: { username: string; password: string },
  destination = "/"
): Promise<void> {
  const appOrigin = new URL(destination, "http://localhost:5173").origin
  await page.goto(destination)

  const signOutButton = page.getByRole("button", { name: "Sign out" })
  const usernameInput = page.locator("#username")

  const visibleSurface = await Promise.race([
    signOutButton.waitFor({ state: "visible", timeout: 20_000 }).then(() => "app"),
    usernameInput.waitFor({ state: "visible", timeout: 20_000 }).then(() => "keycloak"),
  ])

  if (visibleSurface === "app") {
    return
  }

  await usernameInput.fill(credentials.username)
  await page.locator("#password").fill(credentials.password)

  await Promise.all([
    page.waitForURL(url => url.origin === appOrigin, {
      timeout: 20_000,
    }),
    page.locator("#kc-login").click(),
  ])

  await expect(signOutButton).toBeVisible({ timeout: 20_000 })
}
