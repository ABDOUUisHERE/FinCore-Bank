import { test, expect, type Browser } from "@playwright/test";
import { mkdirSync, readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";

const credentials = JSON.parse(readFileSync(".local/credentials.json", "utf8")) as Record<string, { username: string; password: string }>;
async function login(browser: Browser, identity: string, admin = true) {
  const context = await browser.newContext(); const page = await context.newPage();
  await page.goto("http://localhost:" + (admin ? 5174 : 5173) + "/");
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
test("hosted staff PKCE scopes immutable audit, incident replay and support isolation", async ({ browser }) => {
  test.setTimeout(300_000);
  const admin = await login(browser, "admin"), support = await login(browser, "support"), other = await login(browser, "support-second");
  const customer = await login(browser, "customer", false), finance = await login(browser, "finance");
  const call = async (identity: typeof admin, method: string, path: string, body?: object, key?: string, expected = 200) => {
    const response = await identity.page.request.fetch("http://localhost:8280/api/v1" + path, { method, headers: { Authorization: "Bearer " + identity.token, ...(key ? { "Idempotency-Key": key } : {}) }, ...(body ? { data: body } : {}) });
    expect(response.status(), "Scoped operations request " + path).toBe(expected); return response.json();
  };
  // Produce a genuine new domain fact through its owning Java service.
  const profile = await customer.page.request.get("http://localhost:8280/api/v1/customers/me/profile", { headers: { Authorization: "Bearer " + customer.token } });
  if (profile.status() === 404) await call(customer, "POST", "/customers/me/onboarding", { displayName: "Synthetic Operations Customer", countryCode: "MA" }); else expect(profile.status()).toBe(200);
  const accountResponse = await customer.page.request.post("http://localhost:8280/api/v1/accounts", { headers: { Authorization: "Bearer " + customer.token, "Idempotency-Key": crypto.randomUUID() }, data: { currency: "MAD" } });
  expect([201, 202]).toContain(accountResponse.status()); const account = await accountResponse.json();
  await expect.poll(async () => (await call(admin, "GET", "/admin/audit/records?aggregateId=" + account.accountId + "&eventType=account.created")).items.length, { timeout: 90_000, intervals: [1000, 2000] }).toBe(1);
  await admin.page.getByRole("button", { name: "Audit", exact: true }).click(); await expect(admin.page.getByRole("heading", { name: "Central audit", exact: true })).toBeVisible();
  await admin.page.getByLabel("Aggregate UUID", { exact: true }).fill(account.accountId); await admin.page.getByLabel("Exact event type", { exact: true }).fill("account.created"); await admin.page.getByRole("button", { name: "Filter audit", exact: true }).click(); await expect(admin.page.locator("summary").filter({ hasText: "account.created" })).toHaveCount(1);
  for (const width of [1440, 390, 320]) { await admin.page.setViewportSize({ width, height: 1000 }); expect(await admin.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "Audit fits " + width + "px").toBe(true); if (process.env.FINCORE_VISUAL_QA === "1" && width !== 320) { mkdirSync(".local/visual", { recursive: true }); await admin.page.screenshot({ path: ".local/visual/phase-8-audit-" + width + ".png", fullPage: true, animations: "disabled" }); } }
  const page = support.page; await expect(page.getByText("You can manage only incidents opened by your identity.", { exact: false })).toBeVisible(); await expect(page.getByRole("button", { name: "Audit", exact: true })).toHaveCount(0);
  const submissions: { key: string; body: string | null }[] = []; let incidentId = ""; let firstReceipt: object | undefined;
  await page.route("**/api/v1/admin/incidents", async route => {
    if (route.request().method() !== "POST") { await route.continue(); return; }
    const count = submissions.push({ key: route.request().headers()["idempotency-key"], body: route.request().postData() });
    const response = await route.fetch(); expect(response.status()).toBe(200); const receipt = await response.json(); incidentId = receipt.incidentId;
    if (count === 1) { firstReceipt = receipt; await route.abort("failed"); } else { expect(receipt).toEqual(firstReceipt); await route.fulfill({ response }); }
  });
  await page.getByLabel("Resource UUID", { exact: true }).fill(account.accountId); await page.getByRole("button", { name: "Review incident", exact: true }).click(); expect(submissions).toHaveLength(0);
  await page.getByRole("button", { name: "Confirm incident request", exact: true }).click(); await expect(page.getByRole("button", { name: "Retry same incident request", exact: true })).toBeVisible();
  await page.reload(); await page.getByRole("button", { name: "Incidents", exact: true }).click(); await page.getByRole("button", { name: "Retry same incident request", exact: true }).click();
  await expect(page.getByText("Recorded result: open at version 0. Current incident details are refreshed below.")).toBeVisible(); expect(submissions).toHaveLength(2); expect(submissions[1]).toEqual(submissions[0]);
  const selected = page.getByRole("region", { name: "Selected incident", exact: true });
  await expect(selected.getByText("open", { exact: true })).toBeVisible(); await selected.getByRole("button", { name: "Review investigation", exact: true }).click(); await page.getByRole("button", { name: "Confirm incident request", exact: true }).click(); await expect(selected.getByText("investigating", { exact: true })).toBeVisible();
  await selected.getByRole("combobox", { name: "Reason code", exact: true }).selectOption("ISSUE_RESOLVED"); await selected.getByRole("button", { name: "Review resolution", exact: true }).click(); await page.getByRole("button", { name: "Confirm incident request", exact: true }).click(); await expect(selected.getByText("resolved", { exact: true })).toBeVisible();
  const history = await call(support, "GET", "/admin/incidents/" + incidentId + "/history"); expect(history.items).toHaveLength(3); expect(history.items.every((fact: { source: string }) => fact.source === "OPERATIONS")).toBe(true);
  expect((await call(support, "POST", "/admin/incidents", JSON.parse(submissions[0].body!), submissions[0].key))).toEqual(firstReceipt);
  await call(support, "PUT", "/admin/incidents/" + incidentId + "/status", { expectedVersion: 0, status: "INVESTIGATING", reason: "INVESTIGATION_UPDATE" }, crypto.randomUUID(), 409);
  for (const suffix of ["", "/history"]) await call(other, "GET", "/admin/incidents/" + incidentId + suffix, undefined, undefined, 404);
  await call(other, "PUT", "/admin/incidents/" + incidentId + "/status", { expectedVersion: 2, status: "INVESTIGATING", reason: "REOPENED_FOR_REVIEW" }, crypto.randomUUID(), 404);
  const foreignList = await call(other, "GET", "/admin/incidents"); expect(foreignList.items.some((i: { incidentId: string }) => i.incidentId === incidentId)).toBe(false);
  expect((await call(admin, "GET", "/admin/incidents/" + incidentId)).status).toBe("RESOLVED");
  for (const identity of [customer, finance]) for (const path of ["/admin/incidents", "/admin/audit/records", "/admin/operations/me"]) await call(identity, "GET", path, undefined, undefined, 403);
  await call(support, "GET", "/admin/audit/records", undefined, undefined, 403);
  console.info("Operations UI: retained Account fact, original incident receipt, investigation/resolution, version conflict and signed support isolation confirmed.");
  // Broker identity can consume but cannot forge financial/domain facts.
  // Disable producer idempotence only for this probe to test topic Write denial independently of cluster IdempotentWrite denial.
  const denied = spawnSync("docker", ["compose", "exec", "-T", "-e", "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m", "kafka", "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server", "kafka:9092", "--producer.config", "/etc/kafka/secrets/audit.properties", "--topic", "fincore.ledger.journal.posted.v1", "--sync", "--producer-property", "enable.idempotence=false", "--producer-property", "max.block.ms=10000", "--producer-property", "delivery.timeout.ms=12000", "--producer-property", "request.timeout.ms=5000"], { input: "{\"mustNeverPublish\":true}\n", encoding: "utf8", timeout: 60_000 });
  expect(denied.stderr + denied.stdout).toContain("TopicAuthorizationException");
  const poisonKey = crypto.randomUUID(); const poison = JSON.stringify({ privateCanary: "UNTRUSTED_AUDIT_BODY_" + poisonKey });
  const sent = spawnSync("docker", ["compose", "exec", "-T", "-e", "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m", "kafka", "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server", "kafka:9092", "--producer.config", "/etc/kafka/secrets/customer.properties", "--topic", "fincore.customer.created.v1", "--sync", "--property", "parse.key=true", "--property", "key.separator=|"], { input: poisonKey + "|" + poison + "\n", encoding: "utf8", timeout: 60_000 });
  expect(sent.status, "Authorized poison fixture publication").toBe(0);
  const { createHash } = await import("node:crypto"); const digest = createHash("sha256").update(poison).digest("hex");
  await expect.poll(async () => (await call(admin, "GET", "/admin/audit/quarantine")).items.some((item: { bodyHash: string }) => item.bodyHash === digest), { timeout: 60_000, intervals: [1000, 2000] }).toBe(true);
  const logs = spawnSync("docker", ["compose", "logs", "--no-color", "audit-service"], { encoding: "utf8", timeout: 30_000, maxBuffer: 8 * 1024 * 1024 }); expect(logs.status).toBe(0); expect(logs.stdout + logs.stderr).not.toContain("UNTRUSTED_AUDIT_BODY_" + poisonKey);
  for (const width of [1440, 390, 320]) { await page.setViewportSize({ width, height: 1000 }); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), "Operations fit " + width + "px").toBe(true); if (process.env.FINCORE_VISUAL_QA === "1" && width !== 320) { mkdirSync(".local/visual", { recursive: true }); await page.screenshot({ path: ".local/visual/phase-8-incidents-" + width + ".png", fullPage: true, animations: "disabled" }); } }
  console.info("Operations: hosted PKCE, real domain fact, same-key lost-response recovery, immutable incident history, optimistic conflicts, support isolation, finance/customer denial, read-only Audit Kafka identity and redacted durable quarantine confirmed.");
  for (const identity of [admin, support, other, customer, finance]) await identity.context.close();
});
