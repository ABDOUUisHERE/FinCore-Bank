import { test, expect, type Browser, type Page } from "@playwright/test";
import { spawnSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";

let brokerStopped = false;
test.afterEach(() => {
  if (brokerStopped) {
    const result = spawnSync("docker", ["compose", "start", "kafka"], { encoding: "utf8", timeout: 60_000 });
    brokerStopped = false; expect(result.status, "Restore only the FinCore Kafka broker").toBe(0);
  }
});

type Identity = { username: string; password: string };
function fixture<T>(action: string, input?: object): T {
  const result = spawnSync("docker", ["compose", "run", "--rm", "--no-deps", "-T", "customer-fixture", action],
    { input: input ? JSON.stringify(input) : undefined, encoding: "utf8", timeout: 180_000, maxBuffer: 1024 * 1024 });
  // Identity action stdout contains fresh credentials and must never be part of an assertion diagnostic.
  expect(result.status, "Private customer fixture failed; check container readiness.").toBe(0);
  return JSON.parse(result.stdout.trim()) as T;
}
async function login(browser: Browser, identity: Identity) {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto("http://localhost:5173/");
  await page.getByRole("button", { name: "Sign in securely" }).click();
  await expect(page).toHaveURL(/localhost:8180/);
  expect(new URL(page.url()).searchParams.get("code_challenge_method")).toBe("S256");
  await page.getByLabel("Username or email").fill(identity.username);
  await page.getByLabel("Password", { exact: true }).fill(identity.password);
  const tokenResponse = page.waitForResponse(response => response.url().endsWith("/protocol/openid-connect/token") && response.request().method() === "POST");
  await page.getByRole("button", { name: "Sign In", exact: true }).click();
  const response = await tokenResponse;
  expect(response.status()).toBe(200);
  const token = (await response.json()).access_token as string;
  await expect(page.getByText("Identity verified by the customer service")).toBeVisible();
  const active = { context, page, token };
  page.on("request", request => {
    if (request.url().startsWith("http://localhost:8280/api/")) {
      const authorization = request.headers()["authorization"];
      if (authorization?.startsWith("Bearer ")) active.token = authorization.slice(7);
    }
  });
  return active;
}
async function onboardAndOpen(page: Page, name: string) {
  await page.getByLabel("Display name", { exact: true }).fill(name);
  await page.getByLabel("Country code", { exact: true }).fill("MA");
  await page.getByRole("button", { name: "Review profile", exact: true }).click();
  await page.getByRole("button", { name: "Confirm change", exact: true }).click();
  await page.getByRole("button", { name: "Accounts", exact: true }).click();
  await page.getByRole("button", { name: "Open account", exact: true }).click();
  await page.getByRole("combobox", { name: "Account currency", exact: true }).selectOption("MAD");
  await page.getByRole("button", { name: "Confirm open account", exact: true }).click();
  const details = page.getByRole("region", { name: "Selected account details" });
  await expect(details.getByText("active", { exact: true })).toBeVisible({ timeout: 45_000 });
  return (await page.getByTestId("account-reference").innerText()).trim();
}
async function transferDraft(page: Page, destination: string, amount: string, reference: string) {
  await page.getByRole("button", { name: "Continue to recipient" }).click();
  await page.getByRole("combobox", { name: "Recipient", exact: true }).selectOption(destination);
  await page.getByRole("button", { name: "Continue to amount" }).click();
  await page.getByLabel("Amount (MAD)", { exact: true }).fill(amount);
  await page.getByLabel("Reference (optional)", { exact: true }).fill(reference);
  await page.getByRole("button", { name: "Review transfer", exact: true }).click();
}
test("customer screens onboard, save a beneficiary, recover a lost transfer response and confirm actual ledger money", async ({ browser }) => {
  test.setTimeout(480_000);
  const identities = fixture<{ sender: Identity; recipient: Identity }>("identities");
  const recipient = await login(browser, identities.recipient);
  const destination = await onboardAndOpen(recipient.page, "Synthetic UI Recipient");
  const sender = await login(browser, identities.sender);
  const source = await onboardAndOpen(sender.page, "Synthetic UI Customer");
  console.info("Customer UI: both fresh profiles and owned accounts confirmed.");
  await sender.page.getByRole("button", { name: "Notifications", exact: true }).click();
  await sender.page.getByLabel("Simulated emails", { exact: true }).check();
  await sender.page.getByRole("button", { name: "Review notification preferences", exact: true }).click();
  const preferencesSaved = sender.page.waitForResponse(response => response.url().endsWith("/notifications/preferences") && response.request().method() === "PUT");
  await sender.page.getByRole("button", { name: "Confirm change", exact: true }).click();
  expect((await preferencesSaved).status()).toBe(200);
  await expect(sender.page.getByLabel("Simulated emails", { exact: true })).toBeChecked();
  await recipient.page.getByRole("button", { name: "Notifications", exact: true }).click();
  await expect(recipient.page.getByText("Live updates connected", { exact: true })).toBeVisible({ timeout: 30_000 });
  fixture("fund", { customer: sender.token, recipient: recipient.token, source, destination });
  console.info("Customer UI: private synthetic funding confirmed.");
  const page = sender.page;
  await page.getByRole("button", { name: "Beneficiaries", exact: true }).click();
  await page.getByRole("button", { name: "Add beneficiary", exact: true }).click();
  await page.getByLabel("Beneficiary name", { exact: true }).fill("UI Recipient");
  await page.getByLabel("Recipient account reference", { exact: true }).fill(destination);
  await page.getByRole("button", { name: "Review beneficiary", exact: true }).click();
  await page.getByRole("button", { name: "Confirm change", exact: true }).click();
  await expect(page.getByRole("heading", { name: "UI Recipient", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Transfers", exact: true }).click();
  const reference = "Browser transfer " + crypto.randomUUID();
  brokerStopped = true;
  const stop = spawnSync("docker", ["compose", "stop", "-t", "15", "kafka"], { encoding: "utf8", timeout: 60_000 });
  expect(stop.status, "Stop only the FinCore broker for the outage check").toBe(0);
  await transferDraft(page, destination, "100.00", reference);
  const submissions: { key: string | undefined; body: string | null }[] = [];
  await page.route("**/api/v1/transfers", async route => {
    if (route.request().method() !== "POST") { await route.continue(); return; }
    submissions.push({ key: route.request().headers()["idempotency-key"], body: route.request().postData() });
    if (submissions.length === 1) { await route.fetch(); await route.abort("failed"); }
    else await route.continue();
  });
  await page.getByRole("button", { name: "Confirm and send", exact: true }).click();
  await expect(page.getByRole("button", { name: "Retry same transfer", exact: true })).toBeEnabled();
  await page.reload();
  await page.getByRole("button", { name: "Transfers", exact: true }).click();
  await page.getByRole("button", { name: "Retry same transfer", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Transfer completed", exact: true })).toBeVisible({ timeout: 45_000 });
  console.info("Customer UI: browser reload and original request replay confirmed.");
  expect(submissions).toHaveLength(2);
  expect(submissions[1]).toEqual(submissions[0]);
  const result = page.getByRole("region", { name: "Transfer result" });
  const paymentId = (await result.getByText(/^Transfer reference:/).innerText()).split(": ")[1];
  fixture("assert", { customer: sender.token, recipient: recipient.token, source, destination, paymentId });
  const beforeDelivery = await page.request.get("http://localhost:8280/api/v1/notifications", { headers: { Authorization: "Bearer " + sender.token } });
  expect(beforeDelivery.status()).toBe(200);
  expect((await beforeDelivery.json()).items.filter((item: { aggregateId: string }) => item.aggregateId === paymentId)).toHaveLength(0);
  const restart = spawnSync("docker", ["compose", "start", "kafka"], { encoding: "utf8", timeout: 60_000 });
  expect(restart.status, "Resume the same persistent FinCore broker").toBe(0); brokerStopped = false;
  // Recipient stays on the same page: only authenticated SSE may trigger this refresh.
  await expect(recipient.page.getByRole("heading", { name: "Payment received", exact: true })).toBeVisible({ timeout: 90_000 });
  await page.getByRole("button", { name: "Notifications", exact: true }).click();
  const delivered = page.locator(".bank-notifications").first().locator("li").filter({ hasText: paymentId });
  await expect(delivered).toHaveCount(1, { timeout: 90_000 });
  await expect(delivered.getByRole("heading", { name: "Transfer completed", exact: true })).toBeVisible();
  await delivered.getByRole("button", { name: "Mark as read", exact: true }).click();
  await expect(delivered.getByRole("button", { name: "Mark as read", exact: true })).toHaveCount(0);
  const syntheticEmails = page.locator("section").filter({ has: page.getByRole("heading", { name: "Simulated email inbox", exact: true }) });
  await expect(syntheticEmails.getByRole("heading", { name: "Transfer completed", exact: true })).toHaveCount(1, { timeout: 30_000 });
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    if (process.env.FINCORE_VISUAL_QA === "1" && [1440, 390].includes(width)) {
      mkdirSync(".local/visual", { recursive: true });
      await page.screenshot({ path: ".local/visual/phase-5-notifications-" + width + ".png", fullPage: true, animations: "disabled" });
    }
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  console.info("Messaging UI: broker outage preserved money; recovery delivered one inbox fact, synthetic email and live recipient update.");
  await page.getByRole("button", { name: "Transfers", exact: true }).click();
  await page.unroute("**/api/v1/transfers");
  await page.getByRole("button", { name: "Start another transfer", exact: true }).click();
  await transferDraft(page, destination, "12000.00", "Synthetic review then cancellation");
  await page.getByRole("button", { name: "Confirm and send", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Your transfer is under review", exact: true })).toBeVisible({ timeout: 45_000 });
  await page.getByRole("button", { name: "Cancel transfer", exact: true }).click();
  await page.getByRole("button", { name: "Confirm change", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Transfer cancelled", exact: true })).toBeVisible();
  fixture("assert", { customer: sender.token, recipient: recipient.token, source, destination, paymentId });
  await page.getByRole("button", { name: "Start another transfer", exact: true }).click();
  await page.getByRole("button", { name: "History", exact: true }).click();
  await page.getByLabel("Search reference", { exact: true }).fill(reference);
  await page.getByLabel("Minimum amount", { exact: true }).fill("100.00");
  await page.getByLabel("Maximum amount", { exact: true }).fill("100.00");
  await page.getByRole("combobox", { name: "Direction", exact: true }).selectOption("DEBIT");
  await page.getByRole("button", { name: "Apply filters", exact: true }).click();
  await expect(page.getByRole("button", { name: "Export these 1 entries" })).toBeVisible();
  console.info("Customer UI: review cancellation and server-side history filters confirmed.");
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export these 1 entries" }).click();
  const exported = await download;
  expect(exported.suggestedFilename()).toBe("fincore-transactions.csv");
  const csvPath = await exported.path();
  expect(csvPath).toBeTruthy();
  const csv = readFileSync(csvPath!, "utf8");
  expect(csv).toContain('"100.00","MAD"');
  expect(csv).toContain(reference);
  await page.getByRole("button", { name: "Accounts", exact: true }).click();
  await page.getByRole("button", { name: "Freeze account", exact: true }).click();
  await page.getByRole("button", { name: "Confirm change", exact: true }).click();
  await expect(page.getByRole("region", { name: "Selected account details" }).getByText("frozen", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Transfers", exact: true }).click();
  await expect(page.getByRole("heading", { name: "An active account comes first" })).toBeVisible();
  await page.getByRole("button", { name: "Accounts", exact: true }).click();
  await page.getByRole("button", { name: "Unfreeze account", exact: true }).click();
  await page.getByRole("button", { name: "Confirm change", exact: true }).click();
  await expect(page.getByRole("region", { name: "Selected account details" }).getByText("active", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Beneficiaries", exact: true }).click();
  await page.getByLabel("Search beneficiaries", { exact: true }).fill("UI Recipient");
  await page.getByRole("button", { name: "Search beneficiaries", exact: true }).click();
  await page.getByRole("button", { name: "Edit UI Recipient", exact: true }).click();
  await page.getByLabel("Updated beneficiary name", { exact: true }).fill("UI Recipient Renamed");
  await page.getByRole("combobox", { name: "Beneficiary status", exact: true }).selectOption("DISABLED");
  await page.getByRole("button", { name: "Review beneficiary change", exact: true }).click();
  await page.getByRole("button", { name: "Confirm change", exact: true }).click();
  await expect(page.getByRole("heading", { name: "UI Recipient Renamed", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Transfers", exact: true }).click();
  await page.getByRole("button", { name: "Continue to recipient", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Save a recipient first" })).toBeVisible();
  await page.getByRole("button", { name: "Profile", exact: true }).click();
  await page.getByLabel("Display name", { exact: true }).fill("Synthetic UI Verified");
  await page.getByRole("button", { name: "Review profile", exact: true }).click();
  await page.getByRole("button", { name: "Confirm change", exact: true }).click();
  await expect(page.getByLabel("Display name", { exact: true })).toHaveValue("Synthetic UI Verified");
  await page.getByRole("button", { name: "Overview", exact: true }).click();
  await expect(page.getByRole("heading", { name: "A clearer picture, Synthetic UI Verified." })).toBeVisible();
  await expect(page.getByText("15,900.00 MAD", { exact: true }).first()).toBeVisible();
  for (const width of [1440, 768, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    if (process.env.FINCORE_VISUAL_QA === "1" && [1440, 390].includes(width)) {
      mkdirSync(".local/visual", { recursive: true });
      await page.screenshot({ path: ".local/visual/phase-4-dashboard-" + width + ".png", fullPage: true, animations: "disabled" });
    }
  }
  await page.getByRole("button", { name: "Use dark theme" }).click();
  expect(await page.locator(".app").getAttribute("class")).toContain("dark");
  if (process.env.FINCORE_VISUAL_QA === "1") {
    mkdirSync(".local/visual", { recursive: true });
    await page.screenshot({ path: ".local/visual/phase-4-dashboard-dark.png", fullPage: true, animations: "disabled" });
    writeFileSync(".local/phase-4-demo-fixture.json", JSON.stringify({ source, destination }), "utf8");
  }
  await recipient.context.close(); await sender.context.close();
});
