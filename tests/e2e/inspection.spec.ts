import { test, expect, type Browser } from "@playwright/test";
import { mkdirSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";

const credentials = JSON.parse(readFileSync(".local/credentials.json", "utf8")) as Record<string, { username: string; password: string }>;
let ledgerStopped = false;
function ledgerContainer() { const r = spawnSync("docker", ["compose", "ps", "--all", "--quiet", "ledger-service"], { encoding: "utf8", timeout: 30_000 }); expect(r.status).toBe(0); const id = r.stdout.trim(); expect(id).toMatch(/^[a-f0-9]{12,64}$/); return id; }
function resumeLedger() { const r = spawnSync("docker", ["start", ledgerContainer()], { encoding: "utf8", timeout: 60_000 }); expect(r.status).toBe(0); ledgerStopped = false; }
test.afterEach(() => { if (ledgerStopped) resumeLedger(); });
async function login(browser: Browser, identity: string, staff = true) {
  const context = await browser.newContext(); const page = await context.newPage();
  await page.goto("http://localhost:" + (staff ? 5174 : 5173) + "/");
  await expect(page.getByRole("button", { name: "Sign in securely", exact: true })).toBeVisible({ timeout: 60_000 });
  await page.getByRole("button", { name: "Sign in securely", exact: true }).click(); await expect(page).toHaveURL(/localhost:8180/);
  expect(new URL(page.url()).searchParams.get("code_challenge_method")).toBe("S256");
  await page.getByLabel("Username or email").fill(credentials[identity].username); await page.getByLabel("Password", { exact: true }).fill(credentials[identity].password);
  const grant = page.waitForResponse(r => r.url().endsWith("/protocol/openid-connect/token") && r.request().method() === "POST");
  await page.getByRole("button", { name: "Sign In", exact: true }).click(); const response = await grant; expect(response.status()).toBe(200);
  const active = { page, context, token: (await response.json()).access_token as string };
  page.on("request", r => { const bearer = r.headers()["authorization"]; if (r.url().startsWith("http://localhost:8280/api/") && bearer?.startsWith("Bearer ")) active.token = bearer.slice(7); });
  return active;
}

test("staff inspect redacted business state and finance reads a real immutable journal", async ({ browser }) => {
  test.setTimeout(540_000);
  const customer = await login(browser, "customer", false), admin = await login(browser, "admin"), finance = await login(browser, "finance"), support = await login(browser, "support");
  const call = async (identity: typeof admin, method: string, path: string, body?: object, expected = 200) => {
    const response = await identity.page.request.fetch("http://localhost:8280/api/v1" + path, { method, headers: { Authorization: "Bearer " + identity.token, "Idempotency-Key": crypto.randomUUID() }, ...(body ? { data: body } : {}), timeout: 30_000 });
    expect(response.status(), "Business inspection request " + path).toBe(expected); return response.json();
  };
  const profileResponse = await customer.page.request.get("http://localhost:8280/api/v1/customers/me/profile", { headers: { Authorization: "Bearer " + customer.token } });
  if (profileResponse.status() === 404) await call(customer, "POST", "/customers/me/onboarding", { displayName: "Synthetic Inspection Customer", countryCode: "MA" }); else expect(profileResponse.status()).toBe(200);
  const profile = await call(customer, "GET", "/customers/me/profile");
  const accounts: string[] = [];
  for (let n = 0; n < 2; n++) {
    const response = await customer.page.request.post("http://localhost:8280/api/v1/accounts", { headers: { Authorization: "Bearer " + customer.token, "Idempotency-Key": crypto.randomUUID() }, data: { currency: "MAD" }, timeout: 30_000 });
    expect([201, 202]).toContain(response.status()); const account = await response.json(); accounts.push(account.accountId);
    await expect.poll(async () => (await call(customer, "GET", "/accounts/" + account.accountId)).status, { timeout: 60_000 }).toBe("ACTIVE");
  }
  const fixture = spawnSync("docker", ["compose", "run", "--rm", "--no-deps", "-T", "customer-fixture", "card-fund"], { input: JSON.stringify({ customer: customer.token, recipient: customer.token, source: accounts[0], destination: accounts[1] }), encoding: "utf8", timeout: 180_000, maxBuffer: 1024 * 1024 });
  expect(fixture.status, "Private synthetic funding fixture; credentials remain in stdin").toBe(0);
  const accepted = await call(customer, "POST", "/transfers", { sourceAccountId: accounts[0], destinationAccountId: accounts[1], amount: "10.01", currency: "MAD", reference: "Private inspection canary" }, 202);
  await expect.poll(async () => (await call(customer, "GET", "/transfers/" + accepted.paymentId)).status, { timeout: 90_000 }).toBe("COMPLETED");
  const payment = await call(customer, "GET", "/transfers/" + accepted.paymentId);
  const balances = await Promise.all(accounts.map(id => call(customer, "GET", "/accounts/" + id + "/balance")));
  const status = await call(admin, "GET", "/admin/customers/" + profile.customerId);
  expect(Object.keys(status).sort()).toEqual(["createdAt", "customerId", "observedAt", "profileVersion", "status"]);
  const inspected = await call(admin, "GET", "/admin/transactions/" + accepted.paymentId);
  expect(inspected.journalId).toBe(payment.journalId); expect(inspected.status).toBe("COMPLETED"); expect(inspected.history.length).toBeGreaterThan(2);
  for (const field of ["actor", "key", "reference", "instruction", "initial_response", "amount", "currency", "sourceAccountId", "destinationAccountId", "lease_token", "fingerprint"]) expect(inspected).not.toHaveProperty(field);
  expect(JSON.stringify(inspected)).not.toContain("Private inspection canary");
  const journal = await call(finance, "GET", "/admin/ledger/journals/" + payment.journalId);
  expect(journal.total).toBe("10.01"); expect(journal.currency).toBe("MAD"); expect(journal.entries.map((e: { side: string }) => e.side).sort()).toEqual(["CREDIT", "DEBIT"]);
  expect(journal.entries.every((e: { amount: string }) => e.amount === "10.01")).toBe(true);
  const paths = ["/admin/customers/" + profile.customerId, "/admin/transactions/" + accepted.paymentId, "/admin/accounts/" + accounts[0], "/admin/ledger/journals/" + payment.journalId];
  for (const identity of [customer, support]) for (const path of paths) await call(identity, "GET", path, undefined, 403);
  for (const path of paths.slice(0, 3)) await call(finance, "GET", path, undefined, 403);
  await call(admin, "GET", paths[3], undefined, 403);
  for (const path of paths) await call(admin, "POST", path, {}, 403);
  await call(admin, "GET", "/admin/customers/" + crypto.randomUUID(), undefined, 404);
  await call(admin, "GET", "/admin/transactions/" + crypto.randomUUID(), undefined, 404);
  await call(admin, "GET", "/admin/accounts/" + crypto.randomUUID(), undefined, 404);
  await call(finance, "GET", "/admin/ledger/journals/" + crypto.randomUUID(), undefined, 404);
  async function lookup(identity: typeof admin, kind: string, id: string, region: string) {
    await identity.page.getByRole("button", { name: "Inspection", exact: true }).click();
    await identity.page.getByRole("combobox", { name: "Resource type", exact: true }).selectOption(kind);
    await identity.page.getByLabel("Resource UUID", { exact: true }).fill(id); await identity.page.getByRole("button", { name: "Inspect reference", exact: true }).click();
    await expect(identity.page.getByRole("region", { name: region }).getByText(id, { exact: true })).toBeVisible();
    for (const width of [1440, 390, 320]) {
      await identity.page.setViewportSize({ width, height: 1000 }); expect(await identity.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), kind + " fits " + width + "px").toBe(true);
      if (process.env.FINCORE_VISUAL_QA === "1" && width !== 320) { mkdirSync(".local/visual", { recursive: true }); await identity.page.screenshot({ path: ".local/visual/phase-8-inspection-" + kind.toLowerCase() + "-" + width + ".png", fullPage: true, animations: "disabled" }); }
    }
    await identity.page.setViewportSize({ width: 1440, height: 1000 });
  }
  await lookup(admin, "Customer", profile.customerId, "Inspected customer"); await lookup(admin, "Payment", accepted.paymentId, "Inspected payment");
  await expect(admin.page.getByRole("region", { name: "Inspected payment" }).getByText("COMPLETED", { exact: true }).first()).toBeVisible();
  await lookup(finance, "Journal", payment.journalId, "Inspected journal"); await expect(finance.page.getByRole("table")).toContainText("10.01 MAD");
  await expect(support.page.getByRole("button", { name: "Inspection", exact: true })).toHaveCount(0);
  const again = await call(finance, "GET", paths[3]); expect({ ...again, observedAt: null }).toEqual({ ...journal, observedAt: null });
  // Inspect a real owner-confirmed restriction; ADMIN receives no restriction command.
  const ownAccount = await call(customer, "GET", "/accounts/" + accounts[0]);
  const restricted = await customer.page.request.put("http://localhost:8280/api/v1/accounts/" + accounts[0] + "/guard", { headers: { Authorization: "Bearer " + customer.token }, data: { status: "FROZEN", expectedVersion: ownAccount.version }, timeout: 30_000 });
  expect([200, 202]).toContain(restricted.status());
  await expect.poll(async () => (await call(customer, "GET", "/accounts/" + accounts[0])).status, { timeout: 60_000 }).toBe("FROZEN");
  const accountStatus = await call(admin, "GET", paths[2]);
  expect(accountStatus.recordedStatus).toBe("FROZEN"); expect(accountStatus.ledgerState).toBe("OBSERVED"); expect(accountStatus.ledger.status).toBe("FROZEN");
  expect(accountStatus.ledger.guardRevision).toBe(accountStatus.recordedGuardRevision); expect(typeof accountStatus.accountVersion).toBe("string");
  for (const field of ["ownerSubject", "ledgerId", "key", "balance", "booked", "available"]) expect(accountStatus).not.toHaveProperty(field);
  const deniedGuard = await admin.page.request.put("http://localhost:8280/api/v1/admin/accounts/" + accounts[0] + "/guard", { headers: { Authorization: "Bearer " + admin.token }, data: { status: "ACTIVE", expectedVersion: 0 } }); expect(deniedGuard.status()).toBe(403);
  await lookup(admin, "Account", accounts[0], "Inspected account");
  await expect(admin.page.getByRole("region", { name: "Observed Ledger status" }).getByText("FROZEN", { exact: true })).toBeVisible();
  // A dedicated FinCore outage must not be represented as an effective status. Always restore it.
  try {
    ledgerStopped = true; const stopped = spawnSync("docker", ["stop", ledgerContainer()], { encoding: "utf8", timeout: 60_000 }); expect(stopped.status).toBe(0);
    const partial = await call(admin, "GET", paths[2]); expect(partial.ledgerState).toBe("UNAVAILABLE"); expect(partial.ledger).toBeNull(); expect(partial.recordedStatus).toBe("FROZEN");
    await admin.page.getByRole("button", { name: "Refresh account", exact: true }).click();
    await expect(admin.page.getByText("Ledger status is unavailable. The recorded account status is not confirmation of its effective state.", { exact: true })).toBeVisible();
    await expect(admin.page.getByRole("region", { name: "Observed Ledger status" })).toHaveCount(0);
    if (process.env.FINCORE_VISUAL_QA === "1") { await admin.page.setViewportSize({ width: 390, height: 1000 }); await admin.page.screenshot({ path: ".local/visual/phase-8-account-unavailable-390.png", fullPage: true, animations: "disabled" }); }
  } finally { if (ledgerStopped) resumeLedger(); }
  await expect.poll(async () => { const r = await admin.page.request.get("http://localhost:8280/api/v1" + paths[2], { headers: { Authorization: "Bearer " + admin.token }, timeout: 30_000 }); return r.status() === 200 ? (await r.json()).ledgerState : "STARTING"; }, { timeout: 180_000, intervals: [1000, 3000] }).toBe("OBSERVED");
  await admin.page.getByRole("button", { name: "Refresh account", exact: true }).click(); await expect(admin.page.getByRole("region", { name: "Observed Ledger status" })).toBeVisible();
  const recovered = await call(admin, "GET", paths[2]); expect(recovered.accountVersion).toBe(accountStatus.accountVersion); expect(recovered.ledger.guardRevision).toBe(accountStatus.ledger.guardRevision);
  for (let n = 0; n < accounts.length; n++) expect((await call(customer, "GET", "/accounts/" + accounts[n] + "/balance")).booked).toBe(balances[n].booked);
  console.info("Business inspection: real customer/payment/journal/account status, exact decimals, role isolation, unchanged money, real Ledger outage/restore and responsive staff screens confirmed.");
  for (const identity of [customer, admin, finance, support]) await identity.context.close();
});
