import { test, expect, type Browser } from "@playwright/test";
import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";

const credentials = JSON.parse(readFileSync(".local/credentials.json", "utf8")) as Record<string, { username: string; password: string }>;

async function accessToken(browser: Browser, identity: "customer" | "recipient") {
  const context = await browser.newContext();
  const page = await context.newPage();
  page.setDefaultTimeout(15_000);
  await page.goto("http://localhost:5173/");
  await expect(page.getByRole("button", { name: "Sign in securely" })).toBeVisible();
  await page.getByRole("button", { name: "Sign in securely" }).click();
  await expect(page).toHaveURL(/localhost:8180/);
  expect(new URL(page.url()).searchParams.get("code_challenge_method")).toBe("S256");
  await page.getByLabel("Username or email").fill(credentials[identity].username);
  await page.getByLabel("Password", { exact: true }).fill(credentials[identity].password);
  const token = page.waitForResponse(response => response.url().endsWith("/protocol/openid-connect/token") && response.request().method() === "POST");
  await page.getByRole("button", { name: "Sign In", exact: true }).click();
  const response = await token;
  expect(response.status()).toBe(200);
  const value = (await response.json()).access_token as string;
  await context.close();
  return value;
}

test("real PKCE identities complete the authoritative simulated payment journey", async ({ browser }) => {
  test.setTimeout(180_000);
  const customer = await accessToken(browser, "customer");
  const recipient = await accessToken(browser, "recipient");
  const native = process.env.FINCORE_NATIVE_BUSINESS === "1";
  const result = spawnSync(native ? (process.platform === "win32" ? "python" : "python3") : "docker",
    native ? ["scripts/check-business.py"] : ["compose", "run", "--rm", "--no-deps", "-T", "business-check"],
    { input: JSON.stringify({ customer, recipient }), encoding: "utf8", timeout: 150_000, maxBuffer: 1024 * 1024 });
  // Only the runner's bounded safe diagnostics appear here; bearer values never enter arguments/artifacts.
  expect(result.status, result.stdout + result.stderr).toBe(0);
  expect(result.stdout).toContain("PASS: two real PKCE customers");
});
