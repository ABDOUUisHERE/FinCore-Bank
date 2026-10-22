import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
const apiUrl = process.env.FINCORE_API_URL ?? "http://localhost:8280";
const credentials = JSON.parse(
  readFileSync(".local/credentials.json", "utf8"),
) as Record<"customer" | "admin", { username: string; password: string }>;

async function login(page: Page, identity: "customer" | "admin") {
  await page.getByRole("button", { name: "Sign in securely" }).click();
  await expect(page).toHaveURL(/localhost:8180/);
  const url = new URL(page.url());
  expect(url.searchParams.get("code_challenge_method")).toBe("S256");
  expect(url.searchParams.get("code_challenge")).toBeTruthy();
  await page
    .getByLabel("Username or email")
    .fill(credentials[identity].username);
  await page
    .getByLabel("Password", { exact: true })
    .fill(credentials[identity].password);
  const responsePromise = page.waitForResponse(
    (response) =>
      response.url().endsWith("/protocol/openid-connect/token") &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Sign In", exact: true }).click();
  const tokenResponse = await responsePromise;
  expect(tokenResponse.status()).toBe(200);
  return (await tokenResponse.json()).access_token as string;
}

test("hosted PKCE login connects the customer API and logout clears access", async ({
  page,
  request,
}) => {
  const unauthenticated = await request.get(`${apiUrl}/api/v1/customers/me`);
  expect(unauthenticated.status()).toBe(401);
  await page.goto("/");
  const capturedToken = await login(page, "customer");
  await expect(
    page.getByText("Identity verified by the customer service"),
  ).toBeVisible();
  expect(capturedToken).not.toBe("");
  const response = await request.get(`${apiUrl}/api/v1/customers/me`, {
    headers: { Authorization: `Bearer ${capturedToken}` },
  });
  expect(response.status()).toBe(200);
  const subject = await response.json();
  expect(subject.roles).toContain("CUSTOMER");
  expect(subject.subject).toBeTruthy();
  const stored = await page.evaluate(() => ({
    local: Object.keys(localStorage),
    session: Object.keys(sessionStorage),
  }));
  expect(
    [...stored.local, ...stored.session].some((key) =>
      /token|refresh/i.test(key),
    ),
  ).toBe(false);
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Sign in securely" }),
  ).toBeVisible();
});

test("customer role is denied the administration workspace", async ({
  page,
}) => {
  await page.goto("http://localhost:5174/");
  await login(page, "customer");
  await expect(page.getByRole("alert")).toContainText("Access denied");
});

test("staff role opens its shell but does not inherit customer API privileges", async ({
  page,
  request,
}) => {
  await page.goto("http://localhost:5174/");
  const token = await login(page, "admin");
  await expect(page.getByText("Authorized staff session")).toBeVisible();
  const forbidden = await request.get(`${apiUrl}/api/v1/customers/me`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(forbidden.status()).toBe(403);
  const result = await page.evaluate(async () => {
    const keys = Object.keys(localStorage);
    return keys.some((key) => /token/i.test(key));
  });
  expect(result).toBe(false);
  const missing = await request.get(`${apiUrl}/actuator/env`);
  expect(missing.status()).toBe(401);
});

test("workspace remains usable on mobile and desktop", async ({ page }) => {
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: "Sign in securely" }),
  ).toBeVisible();
  for (const width of [1440, 768, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
      `No horizontal overflow at ${width}px`,
    ).toBe(true);
    if (width === 1440 || width === 390) {
      await page.screenshot({
        path: `test-results/foundation-${width}.png`,
        fullPage: true,
        animations: "disabled",
      });
    }
  }
  await page.getByRole("button", { name: "Open navigation" }).click();
  await page.getByRole("button", { name: "Security", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Peace of mind, built in." }),
  ).toBeVisible();
});
