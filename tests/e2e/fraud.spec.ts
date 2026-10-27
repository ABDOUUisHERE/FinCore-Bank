import { test, expect, type Browser, type Page } from "@playwright/test";
import { spawnSync } from "node:child_process";
import { mkdirSync, readFileSync } from "node:fs";

type Identity = { username: string; password: string };
let fraudStopped = false;
function startFraud() {
  // Compose start also traverses bootstrap dependencies. Resume only the existing service container.
  const selected = spawnSync("docker", ["compose", "ps", "--all", "--quiet", "fraud-service"], { encoding: "utf8", timeout: 30_000 });
  expect(selected.status, "Resolve the existing FinCore Fraud container").toBe(0);
  const id = selected.stdout.trim(); expect(id).toMatch(/^[a-f0-9]{12,64}$/);
  return spawnSync("docker", ["start", id], { encoding: "utf8", timeout: 60_000 });
}
test.afterEach(() => {
  if (fraudStopped) {
    const result = startFraud();
    fraudStopped = false; expect(result.status, "Restore only FinCore Fraud after the outage acceptance check").toBe(0);
  }
});
function fixture<T>(action: string, input?: object): T {
  const r = spawnSync("docker", ["compose", "run", "--rm", "--no-deps", "-T", "customer-fixture", action], { input: input ? JSON.stringify(input) : undefined, encoding: "utf8", timeout: 180_000, maxBuffer: 1024 * 1024 });
  expect(r.status, "Private fraud acceptance fixture failed; inspect scoped service readiness.").toBe(0);
  return JSON.parse(r.stdout.trim()) as T;
}
async function login(browser: Browser, user: Identity, admin = false) {
  const context = await browser.newContext(); const page = await context.newPage();
  await page.goto("http://localhost:" + (admin ? "5174" : "5173") + "/"); await page.getByRole("button", { name: "Sign in securely", exact: true }).click();
  await expect(page).toHaveURL(/localhost:8180/); expect(new URL(page.url()).searchParams.get("code_challenge_method")).toBe("S256");
  await page.getByLabel("Username or email").fill(user.username); await page.getByLabel("Password", { exact: true }).fill(user.password);
  const grant = page.waitForResponse(r => r.url().endsWith("/protocol/openid-connect/token") && r.request().method() === "POST");
  await page.getByRole("button", { name: "Sign In", exact: true }).click(); const response = await grant; expect(response.status()).toBe(200);
  const active = { context, page, token: (await response.json()).access_token as string };
  page.on("request", r => { const bearer = r.headers()["authorization"]; if (r.url().startsWith("http://localhost:8280/api/") && bearer?.startsWith("Bearer ")) active.token = bearer.slice(7); });
  return active;
}
async function account(page: Page, name: string) {
  await expect(page.getByText("Identity verified by the customer service")).toBeVisible();
  await page.getByLabel("Display name", { exact: true }).fill(name); await page.getByLabel("Country code", { exact: true }).fill("MA");
  await page.getByRole("button", { name: "Review profile", exact: true }).click(); await page.getByRole("button", { name: "Confirm change", exact: true }).click();
  await page.getByRole("button", { name: "Accounts", exact: true }).click(); await page.getByRole("button", { name: "Open account", exact: true }).click();
  await page.getByRole("combobox", { name: "Account currency", exact: true }).selectOption("MAD"); await page.getByRole("button", { name: "Confirm open account", exact: true }).click();
  await expect(page.getByRole("region", { name: "Selected account details" }).getByText("active", { exact: true })).toBeVisible({ timeout: 60_000 });
  return (await page.getByTestId("account-reference").innerText()).trim();
}
test("fraud analyst investigates a real customer review, strict blocks and a fail-closed service outage", async ({ browser }) => {
  test.setTimeout(600_000);
  const identities = fixture<{ sender: Identity; recipient: Identity }>("identities");
  const recipient = await login(browser, identities.recipient); const destination = await account(recipient.page, "Synthetic Fraud Recipient");
  const sender = await login(browser, identities.sender); const source = await account(sender.page, "Synthetic Fraud Customer");
  fixture("fund", { customer: sender.token, recipient: recipient.token, source, destination });
  const customerApi = async (method: string, path: string, body?: object, key?: string) => {
    const response = await sender.page.request.fetch("http://localhost:8280/api/v1" + path, { method, headers: { Authorization: "Bearer " + sender.token, ...(key ? { "Idempotency-Key": key } : {}) }, ...(body ? { data: body } : {}) });
    expect(response.status(), "Owned customer request must return a recorded result").toBe(method === "POST" && path === "/transfers" ? 202 : method === "POST" && path === "/beneficiaries" ? 201 : 200);
    return response.json();
  };
  const beneficiary = await customerApi("POST", "/beneficiaries", { label: "Fraud Recipient", destinationAccountId: destination }, crypto.randomUUID());
  expect(beneficiary.destinationAccountId).toBe(destination);
  const start = (amount: string) => customerApi("POST", "/transfers", { sourceAccountId: source, destinationAccountId: destination, amount, currency: "MAD", reference: "Synthetic fraud acceptance" }, crypto.randomUUID());
  const inspected = (id: string) => customerApi("GET", "/transfers/" + id);
  const verify = (id: string, expectedStatus: string, expectedSource: string, expectedDestination: string, expectedPostings: number, amount?: string) => fixture("fraud-assert", { customer: sender.token, recipient: recipient.token, source, destination, paymentId: id, expectedStatus, expectedSource, expectedDestination, expectedPostings, amount });
  const reviewed = await start("6000.00");
  await expect.poll(async () => (await inspected(reviewed.paymentId)).status, { timeout: 90_000 }).toBe("PENDING_RISK_REVIEW");
  verify(reviewed.paymentId, "PENDING_RISK_REVIEW", "16000.00", "0.00", 0);
  const credentials = JSON.parse(readFileSync(".local/credentials.json", "utf8")) as Record<string, Identity>;
  const analyst = await login(browser, credentials.analyst, true);
  await expect(analyst.page.getByRole("heading", { name: "Latest fraud cases", exact: true })).toBeVisible();
  await analyst.page.getByRole("button", { name: "Investigate payment " + reviewed.paymentId }).click();
  await expect(analyst.page.getByText("new beneficiary high value", { exact: true })).toBeVisible();
  await analyst.page.getByLabel("Decision reason code", { exact: true }).fill("VERIFIED_SIMULATION_CONTEXT");
  await analyst.page.getByRole("button", { name: "Review investigation decision", exact: true }).click();
  const decisionSaved = analyst.page.waitForResponse(r => r.url().endsWith("/decisions") && r.request().method() === "POST");
  await analyst.page.getByRole("button", { name: "Confirm decision", exact: true }).click(); expect((await decisionSaved).status()).toBe(200);
  await expect(analyst.page.getByText("Decision recorded. Payment authorization is rechecked separately.")).toBeVisible();
  await expect.poll(async () => (await inspected(reviewed.paymentId)).status, { timeout: 90_000 }).toBe("COMPLETED");
  verify(reviewed.paymentId, "COMPLETED", "10000.00", "6000.00", 1, "6000.00");
  const alerts = await analyst.page.request.get("http://localhost:8280/api/v1/admin/fraud-alerts", { headers: { Authorization: "Bearer " + analyst.token } });
  expect(alerts.status()).toBe(200);
  const alert = ((await alerts.json()).items as { paymentId: string; caseId: string; verdict: string; alertId: string }[]).find(a => a.paymentId === reviewed.paymentId);
  expect(alert?.verdict).toBe("REVIEW"); expect(alert?.alertId).toMatch(/^[a-f0-9-]{36}$/);
  const caseState = await analyst.page.request.get("http://localhost:8280/api/v1/admin/fraud-cases/" + alert?.caseId, { headers: { Authorization: "Bearer " + analyst.token } });
  expect(caseState.status()).toBe(200); const recordedCase = await caseState.json();
  expect(recordedCase.payment.status).toBe("COMPLETED"); expect(recordedCase.payment.journalId).toBe((await inspected(reviewed.paymentId)).journalId);
  expect(recordedCase.payment.riskAssessmentId).toBe(recordedCase.assessment.assessmentId);
  console.info("Fraud UI: genuine analyst review approved; actual ledger has one balanced transfer and durable assessment reference.");
  for (const width of [1440, 390, 320]) {
    await analyst.page.setViewportSize({ width, height: 1000 }); expect(await analyst.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (process.env.FINCORE_VISUAL_QA === "1" && width !== 320) { mkdirSync(".local/visual", { recursive: true }); await analyst.page.screenshot({ path: ".local/visual/phase-6-fraud-" + width + ".png", fullPage: true, animations: "disabled" }); }
  }
  await analyst.page.setViewportSize({ width: 1440, height: 1000 });
  const blocked = await start("100000.01"); await expect.poll(async () => (await inspected(blocked.paymentId)).status, { timeout: 90_000 }).toBe("REJECTED");
  verify(blocked.paymentId, "REJECTED", "10000.00", "6000.00", 0);
  fraudStopped = true; const stopped = spawnSync("docker", ["compose", "stop", "-t", "15", "fraud-service"], { encoding: "utf8", timeout: 60_000 }); expect(stopped.status).toBe(0);
  const withheld = await start("1.00");
  await expect.poll(async () => (await inspected(withheld.paymentId)).code, { timeout: 45_000 }).toBe("DEPENDENCY_UNAVAILABLE");
  verify(withheld.paymentId, "VALIDATING", "10000.00", "6000.00", 0);
  const restarted = startFraud(); expect(restarted.status).toBe(0); fraudStopped = false;
  await expect.poll(async () => (await inspected(withheld.paymentId)).status, { timeout: 180_000, intervals: [1000, 2000, 4000] }).toBe("COMPLETED");
  verify(withheld.paymentId, "COMPLETED", "9999.00", "6001.00", 1, "1.00");
  console.info("Fraud outage: no debit before assessment; the same durable payment recovered exactly one journal after service restart.");
  const ruleUrl = "http://localhost:8280/api/v1/admin/fraud-rules";
  const ruleHeaders = () => ({ Authorization: "Bearer " + analyst.token });
  const beforeRules = await analyst.page.request.get(ruleUrl, { headers: ruleHeaders() }); expect(beforeRules.status()).toBe(200);
  const original = await beforeRules.json() as { version: number; rules: Record<string, unknown> & { rapidCount: number } };
  const changedCount = original.rules.rapidCount === 12 ? 13 : 12;
  const changedRules = { ...original.rules, rapidCount: changedCount };
  try {
    await analyst.page.getByRole("button", { name: "Fraud rules", exact: true }).click();
    await analyst.page.getByLabel("Same recipient count in ten seconds", { exact: true }).fill(String(changedCount));
    await analyst.page.getByLabel("Rule change reason code", { exact: true }).fill("ACCEPTANCE_RULE_CHANGE");
    await analyst.page.getByRole("button", { name: "Review rule changes", exact: true }).click();
    const published = analyst.page.waitForResponse(r => r.url() === ruleUrl && r.request().method() === "PUT");
    await analyst.page.getByRole("button", { name: "Confirm rule changes", exact: true }).click();
    const saved = await published; expect(saved.status()).toBe(200); const version = await saved.json();
    expect(version.version).toBe(original.version + 1); expect(version.rules.rapidCount).toBe(changedCount);
    await expect(analyst.page.getByText("Current rules version:")).toContainText(String(original.version + 1));
  } finally {
    const currentResponse = await analyst.page.request.get(ruleUrl, { headers: ruleHeaders() }); expect(currentResponse.status()).toBe(200);
    const current = await currentResponse.json();
    if (current.version !== original.version) {
      expect(current.reason, "Only restore the exact acceptance change; preserve unrelated concurrent rule changes").toBe("ACCEPTANCE_RULE_CHANGE");
      expect(current.rules).toEqual(changedRules);
      const restored = await analyst.page.request.put(ruleUrl, { headers: ruleHeaders(), data: { expectedVersion: current.version, rules: original.rules, reason: "ACCEPTANCE_RULE_RESTORE" } });
      expect(restored.status()).toBe(200); expect((await restored.json()).rules).toEqual(original.rules);
    }
  }
  console.info("Fraud rules: hosted analyst confirmed a new immutable version; original thresholds restored with another audited version.");
  const denied = await sender.page.request.get("http://localhost:8280/api/v1/admin/fraud-cases", { headers: { Authorization: "Bearer " + sender.token } }); expect(denied.status()).toBe(403);
  const ordinaryAdmin = await login(browser, credentials.admin, true);
  const forbidden = await ordinaryAdmin.page.request.get("http://localhost:8280/api/v1/admin/fraud-cases", { headers: { Authorization: "Bearer " + ordinaryAdmin.token } }); expect(forbidden.status()).toBe(403);
  await expect(ordinaryAdmin.page.getByRole("button", { name: "Fraud cases", exact: true })).toHaveCount(0);
  await ordinaryAdmin.context.close(); await analyst.context.close(); await sender.context.close(); await recipient.context.close();
});
