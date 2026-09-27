import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end smoke against a running stack (the compose `dashboard` service, or `pnpm start`).
 * PGLENS_DASHBOARD_URL points at it; PGLENS_ADMIN_PASSWORD is the admin login `make up` generated.
 */
export default defineConfig({
  testDir: "./e2e",
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: "list",
  use: {
    baseURL: process.env.PGLENS_DASHBOARD_URL ?? "http://localhost:3000",
    // Traces record typed values (the admin password), so never in CI, where they could be shared.
    trace: process.env.CI ? "off" : "retain-on-failure",
  },
  // "chromium": the full browser in new headless mode, not the separate headless shell (install it
  // with `playwright install --no-shell chromium`). PLAYWRIGHT_CHANNEL=chrome uses an installed Chrome.
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"], channel: process.env.PLAYWRIGHT_CHANNEL ?? "chromium" },
    },
  ],
});
