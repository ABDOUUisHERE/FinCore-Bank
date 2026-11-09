import { test, expect, type Browser } from "@playwright/test";
import { mkdirSync, readFileSync } from "node:fs";

const credentials = JSON.parse(readFileSync(".local/credentials.json", "utf8")) as Record<string, { username: string; password: string }>;
async function login(browser: Browser, identity: string, staff = true) {
  const context = await browser.newContext(), page = await context.newPage();
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

for (const kind of ["account", "payment"] as const) test(`ADMIN grants temporary ${kind} scope, support consults, revocation and resolution close access`, async ({ browser }) => {
  test.setTimeout(300_000);
  const customer = await login(browser, "customer", false), admin = await login(browser, "admin"), support = await login(browser, "support"), other = await login(browser, "support-second");
  const call = async (identity: typeof admin, method: string, path: string, body?: object, key?: string, expected = 200) => {
    const response = await identity.page.request.fetch("http://localhost:8280/api/v1" + path, { method, headers: { Authorization: "Bearer " + identity.token, ...(key ? { "Idempotency-Key": key } : {}) }, ...(body ? { data: body } : {}), timeout: 35_000 });
    expect(response.status(), "Scoped request " + path).toBe(expected); return response.json();
  };
  try {
    const profile = await customer.page.request.get("http://localhost:8280/api/v1/customers/me/profile", { headers: { Authorization: "Bearer " + customer.token } });
    if (profile.status() === 404) await call(customer,"POST","/customers/me/onboarding",{ displayName: "Synthetic Support Customer", countryCode: "MA" }); else expect(profile.status()).toBe(200);
    const created = await customer.page.request.post("http://localhost:8280/api/v1/accounts", { headers: { Authorization: "Bearer " + customer.token, "Idempotency-Key": crypto.randomUUID() }, data: { currency: "MAD" } });
    expect([201,202]).toContain(created.status()); const account = await created.json();
    await expect.poll(async () => (await call(admin,"GET","/admin/accounts/" + account.accountId)).recordedStatus, { timeout: 60_000 }).toBe("ACTIVE");
    let resourceId = account.accountId;
    if (kind === "payment") {
      const destinationResponse = await customer.page.request.post("http://localhost:8280/api/v1/accounts", { headers: { Authorization: "Bearer " + customer.token, "Idempotency-Key": crypto.randomUUID() }, data: { currency: "MAD" } });
      expect([201,202]).toContain(destinationResponse.status()); const destination = await destinationResponse.json();
      await expect.poll(async () => (await call(customer,"GET","/accounts/" + destination.accountId)).status, { timeout: 60_000 }).toBe("ACTIVE");
      const accepted = await call(customer,"POST","/transfers",{sourceAccountId:account.accountId,destinationAccountId:destination.accountId,amount:"0.01",currency:"MAD",reference:"Private support payment canary"},crypto.randomUUID(),202);
      resourceId = accepted.paymentId;
      await expect.poll(async () => (await call(customer,"GET","/transfers/"+resourceId)).status, { timeout: 90_000 }).toMatch(/^(FAILED|REJECTED)$/);
    }
    const beforePayment = kind === "payment" ? await call(customer,"GET","/transfers/"+resourceId) : null;
    const beforeBalance = await call(customer,"GET","/accounts/"+account.accountId+"/balance");
    const incident = await call(support,"POST","/admin/incidents",{ resourceKind: kind.toUpperCase(), resourceId, severity: "MEDIUM", reason: "CUSTOMER_REPORTED" },crypto.randomUUID());
    const base = "/admin/incidents/" + incident.incidentId;
    await call(support,"POST",base+"/"+kind+"-status",{},undefined,403);
    await call(other,"POST",base+"/"+kind+"-status",{},undefined,403);
    await call(other,"GET",base+"/"+kind+"-access",undefined,undefined,404);
    await call(support,"PUT",base+"/"+kind+"-access",{ expectedVersion: "0", action: "ISSUE", minutes: 15, reason: "CONTROL_REVIEW" },crypto.randomUUID(),403);
    const openIncident = async (identity: typeof admin) => {
      await identity.page.getByRole("button", { name: "Incidents", exact: true }).click(); await identity.page.getByRole("button", { name: "Refresh incidents", exact: true }).click();
      const target = identity.page.getByRole("button", { name: "Open incident " + incident.incidentId.slice(-8), exact: true });
      for (let i=0;i<10;i++) { if(await target.count())break; const more=identity.page.getByRole("button",{name:"Load more incidents",exact:true}); if(await more.count())await more.click(); else await identity.page.waitForTimeout(300); }
      await target.click(); await expect(identity.page.getByRole("region",{name:"Temporary "+kind+" access",exact:true})).toBeVisible();
    };
    await openIncident(admin);
    const grants: { key: string; body: string | null }[] = []; let original: unknown;
    await admin.page.route("**/api/v1" + base + "/" + kind + "-access", async route => {
      if(route.request().method()!=="PUT"){await route.continue();return;}
      const n=grants.push({key:route.request().headers()["idempotency-key"],body:route.request().postData()}); const response=await route.fetch(); expect(response.status()).toBe(200);
      const receipt=await response.json(); if(n===1){original=receipt;await route.abort("failed");}else{expect(receipt).toEqual(original);await route.fulfill({response});}
    });
    await admin.page.getByRole("button",{name:"Review temporary access",exact:true}).click(); expect(grants).toHaveLength(0);
    await admin.page.getByRole("button",{name:"Confirm incident request",exact:true}).click(); await expect(admin.page.getByRole("button",{name:"Retry same incident request",exact:true})).toBeVisible();
    await admin.page.reload(); await admin.page.getByRole("button",{name:"Incidents",exact:true}).click(); await admin.page.getByRole("button",{name:"Retry same incident request",exact:true}).click();
    await expect(admin.page.getByText("Recorded access result: active at version 1. Current permission is refreshed below.")).toBeVisible(); expect(grants).toHaveLength(2); expect(grants[1]).toEqual(grants[0]);
    await admin.page.unroute("**/api/v1" + base + "/" + kind + "-access");
    await openIncident(support); const panel=support.page.getByRole("region",{name:"Temporary "+kind+" access",exact:true});
    await expect(panel.getByRole("button",{name:"Review temporary access",exact:true})).toHaveCount(0);
    const reads: string[]=[];support.page.on("request",r=>{if(r.url().endsWith(base+"/"+kind+"-status"))reads.push(r.method());}); expect(reads).toHaveLength(0);
    const observed=support.page.waitForResponse(r=>r.url().endsWith(base+"/"+kind+"-status")); await panel.getByRole("button",{name:"Consult scoped "+kind+" status",exact:true}).click();
    const response=await observed;expect(response.status()).toBe(200);expect(response.headers()["cache-control"]).toContain("no-store");const status=await response.json();
    if(kind==="account"){expect(status.accountId).toBe(account.accountId);expect(status.ledgerState).toBe("OBSERVED");}
    else {expect(Object.keys(status).sort()).toEqual(["createdAt","kind","observedAt","paymentId","status","version"]);expect(status.paymentId).toBe(resourceId);expect(status.status).toBe(beforePayment.status);expect(status.version).toBe(String(beforePayment.version));expect(status.kind).toBe("TRANSFER");expect(Number.isFinite(Date.parse(status.observedAt))).toBe(true);expect(JSON.stringify(status)).not.toContain("Private support payment canary");}
    for(const field of ["customerId","ownerSubject","ledgerAccountId","balance","entries"])expect(status).not.toHaveProperty(field);
    await expect(panel.getByRole("region",{name:"Scoped "+kind+" observation",exact:true})).toBeVisible();expect(reads).toEqual(["POST"]);
    await call(other,"POST",base+"/"+kind+"-status",{},undefined,403);await call(admin,"POST",base+"/"+kind+"-status",{},undefined,403);
    await call(support,"GET","/admin/accounts/"+account.accountId,undefined,undefined,403);
    const adminPanel=admin.page.getByRole("region",{name:"Temporary "+kind+" access",exact:true});await adminPanel.getByRole("button",{name:"Review access revocation",exact:true}).click();await admin.page.getByRole("button",{name:"Confirm incident request",exact:true}).click();
    await expect(admin.page.getByText("Recorded access result: revoked at version 2. Current permission is refreshed below.")).toBeVisible();
    await panel.getByRole("button",{name:"Consult scoped "+kind+" status",exact:true}).click();await expect(panel.getByRole("alert")).toBeVisible();await expect(panel.getByRole("region",{name:"Scoped "+kind+" observation",exact:true})).toHaveCount(0);
    await call(admin,"PUT",base+"/"+kind+"-access",{expectedVersion:"2",action:"ISSUE",minutes:5,reason:"CONTROL_REVIEW"},crypto.randomUUID());
    await call(support,"PUT",base+"/status",{expectedVersion:0,status:"INVESTIGATING",reason:"INVESTIGATION_UPDATE"},crypto.randomUUID());
    await call(support,"PUT",base+"/status",{expectedVersion:1,status:"RESOLVED",reason:"ISSUE_RESOLVED"},crypto.randomUUID());
    const closed=await call(support,"GET",base+"/"+kind+"-access");expect(closed.state).toBe("REVOKED");expect(closed.reason).toBe("INCIDENT_RESOLVED");expect(closed.version).toBe("4");
    await call(support,"PUT",base+"/status",{expectedVersion:2,status:"INVESTIGATING",reason:"REOPENED_FOR_REVIEW"},crypto.randomUUID());await call(support,"POST",base+"/"+kind+"-status",{},undefined,403);
    const history=await call(admin,"GET",base+"/history");const outcomes=history.items.filter((f:{eventType:string})=>f.eventType==="support."+kind+".access").map((f:{details:{outcome:string}})=>f.details.outcome);
    expect(outcomes).toContain("DISCLOSED");expect(outcomes).toContain("FOREIGN_INCIDENT");expect(outcomes).toContain("GRANT_REVOKED");
    expect(JSON.stringify(history)).not.toMatch(/recordedStatus|availableBalance|ownerSubject|access_token/);
    for(const width of [1440,390,320]){await support.page.setViewportSize({width,height:1000});expect(await support.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),"Support view fits "+width+"px").toBe(true);if(process.env.FINCORE_VISUAL_QA==="1"&&width!==320){mkdirSync(".local/visual",{recursive:true});await support.page.screenshot({path:".local/visual/phase-8-support-"+kind+"-access-"+width+".png",fullPage:true,animations:"disabled"});}}
    expect(await call(customer,"GET","/accounts/"+account.accountId+"/balance")).toEqual(beforeBalance);
    if(kind==="payment")expect(await call(customer,"GET","/transfers/"+resourceId)).toEqual(beforePayment);
    console.info(kind+" support access: hosted PKCE, exact grant receipt replay, actual Account/Ledger status, foreign support denial, revocation masking, resolution/reopening and redacted retained decisions confirmed.");
  } finally {for(const identity of [customer,admin,support,other])await identity.context.close();}
});
