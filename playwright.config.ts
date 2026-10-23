import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests/e2e",
  workers: 1,
  retries: 0,
  timeout: 60_000,
  // Cold OIDC discovery and the first JWT/JWKS request can exceed five seconds locally.
  expect: { timeout: 15_000 },
  use: {
    baseURL: "http://localhost:5173",
    headless: true,
    actionTimeout: 15_000,
    trace: "off",
    screenshot: "off",
  },
  reporter: [["list"]],
});
