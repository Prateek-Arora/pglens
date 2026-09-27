import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

/**
 * The 4B smoke (phase_4.md Step 9): log in → add a database → data appears → query detail → copy
 * the DDL, with axe on every page. Runs against the compose stack after `scripts/e2e.sh` has seeded
 * the demo database and registered its agent; the LLM is off, so explanations are the template.
 */
const password = process.env.PGLENS_ADMIN_PASSWORD;
const demo = process.env.PGLENS_E2E_DB ?? "demo";

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByLabel("Username").fill("admin");
  await page.getByLabel("Password").fill(password!);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Databases", level: 1 })).toBeVisible();
}

async function expectAccessible(page: Page) {
  // Check the page as a reader sees it: after the streamed content replaced the loading skeleton.
  await expect(page.getByRole("status", { name: "Loading" })).toHaveCount(0);
  await expect(page.locator("h1").first()).toBeVisible();
  const axe = await new AxeBuilder({ page }).analyze();
  expect(axe.violations, axe.violations.map((v) => v.id).join(", ")).toEqual([]);
}

test.beforeEach(() => {
  test.skip(!password, "PGLENS_ADMIN_PASSWORD is not set");
});

test("an admin adds a database, gets its agent token once, and deletes it", async ({ page }) => {
  const name = `e2e-${Date.now()}`;
  await signIn(page);
  await expectAccessible(page);

  await page.getByLabel("Name").fill(name);
  await page.getByRole("button", { name: "Add database" }).click();
  const created = page.getByRole("status").filter({ hasText: `Registered ${name}` });
  await expect(created).toBeVisible();
  await expect(created).toContainText("PGLENS_AGENT_TOKEN=pglens_");
  await expect(created).toContainText("can't be shown again");

  await page.goto("/");
  await expect(page.getByRole("link", { name })).toBeVisible();
  await expect(
    page.getByRole("listitem").filter({ hasText: name }).getByText("Waiting for the agent"),
  ).toBeVisible();

  await page.goto("/settings");
  await expectAccessible(page);
  const disclosure = page.locator("summary", { hasText: `Delete ${name}` });
  await disclosure.click();
  await page.getByLabel(`Type ${name} to confirm`).fill(name);
  await page.getByRole("button", { name: `Delete ${name}` }).click();
  await expect(disclosure).toHaveCount(0);
});

test("the demo database's data appears, down to a query and its index", async ({
  page,
  context,
}) => {
  test.setTimeout(300_000);
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await signIn(page);

  // The agent samples every 15 s and analysis runs every 30 s: wait for measured activity and a
  // planner-validated recommendation to arrive.
  await page.goto(`/db/${demo}?window=24h`);
  const validated = page.getByRole("row").filter({ hasText: "Planner-validated" });
  await expect(async () => {
    await page.reload();
    await expect(validated.first()).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 240_000, intervals: [10_000] });
  await expect(page.getByRole("table").locator('[data-kind="measured"]').first()).toBeVisible();
  await expectAccessible(page);

  await validated.first().getByRole("link").click();
  await expect(page.getByRole("heading", { name: "Suggested index" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Plan", exact: true })).toBeVisible();
  await expect(page.getByText("Planner-validated ≠ safe").first()).toBeVisible();
  // No LLM configured: the explanation is PgLens's own template, and says so.
  await expect(page.getByText("Written by PgLens from the facts").first()).toBeVisible();
  await expect(page.locator('[data-kind="estimate"]').first()).toBeVisible();
  await expectAccessible(page);

  await page.getByRole("button", { name: "Copy the CREATE INDEX statement" }).first().click();
  const copied = await page.evaluate(() => navigator.clipboard.readText());
  expect(copied).toMatch(/^CREATE INDEX /);

  for (const path of [`/db/${demo}/trends`, `/db/${demo}/recommendations`, "/recommendations"]) {
    await page.goto(path);
    await expectAccessible(page);
  }
});
