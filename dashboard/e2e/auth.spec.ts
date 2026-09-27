import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

const password = process.env.PGLENS_ADMIN_PASSWORD;

test("a signed-out visitor is sent to the login page, which passes axe", async ({ page }) => {
  const response = await page.goto("/");
  await expect(page).toHaveURL(/\/login\?next=%2F$/);
  await expect(page.getByRole("heading", { name: "Sign in to PgLens" })).toBeVisible();

  const csp = response?.headers()["content-security-policy"] ?? "";
  expect(csp).toMatch(/script-src 'self' 'nonce-[A-Za-z0-9+/=]+' 'strict-dynamic'/);
  expect(csp).toContain("frame-ancestors 'none'");

  const axe = await new AxeBuilder({ page }).analyze();
  expect(axe.violations).toEqual([]);
});

test("a wrong password is refused with a message", async ({ page }) => {
  await page.goto("/login");
  // An unknown user, so the login throttle never counts against the real admin.
  await page.getByLabel("Username").fill("nobody-e2e");
  await page.getByLabel("Password").fill("not-the-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "Wrong" })).toHaveText(
    "Wrong username or password.",
  );
});

test("the admin signs in, lands where they were going, and signs out", async ({
  page,
  context,
}) => {
  test.skip(!password, "PGLENS_ADMIN_PASSWORD is not set");
  await page.goto("/recommendations");
  await expect(page).toHaveURL(/\/login\?next=%2Frecommendations$/);
  await page.getByLabel("Username").fill("admin");
  await page.getByLabel("Password").fill(password!);
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page).toHaveURL(/\/recommendations$/);
  const session = (await context.cookies()).find((c) => c.name.endsWith("pglens_session"));
  expect(session?.httpOnly).toBe(true);
  expect(session?.sameSite).toBe("Lax");

  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect((await context.cookies()).find((c) => c.name.endsWith("pglens_session"))).toBeUndefined();
});
