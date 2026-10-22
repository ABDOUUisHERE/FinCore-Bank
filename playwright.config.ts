import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests/e2e",
  workers: 1,
  retries: 0,
  timeout: 60_000,
  use: {
    baseURL: "http://localhost:5173",
    headless: true,
    trace: "off",
    screenshot: "off",
  },
  reporter: [["list"]],
});
