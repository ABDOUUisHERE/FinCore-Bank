import { test, expect, type Browser } from "@playwright/test";
import { mkdirSync, readFileSync } from "node:fs";

const credentials = JSON.parse(readFileSync(".local/credentials.json", "utf8")) as Record<string, { username: string; password: string }>;
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

test("ADMIN finds a real profile by name without URL disclosure or profile mutation", async ({ browser }) => {
  test.setTimeout(240_000);
  const customer = await login(browser,"customer",false), admin = await login(browser,"admin"), support = await login(browser,"support"), finance = await login(browser,"finance");
  try {
    const endpoint = "http://localhost:8280/api/v1/admin/customers/search";
    const profileUrl = "http://localhost:8280/api/v1/customers/me/profile";
    let profileResponse = await customer.page.request.get(profileUrl,{ headers: { Authorization: "Bearer " + customer.token } });
    if (profileResponse.status() === 404) {
      const onboard = await customer.page.request.post("http://localhost:8280/api/v1/customers/me/onboarding",{ headers: { Authorization: "Bearer " + customer.token },data: { displayName: "Synthetic Search Customer", countryCode: "MA" } }); expect(onboard.status()).toBe(200);
      profileResponse = await customer.page.request.get(profileUrl,{ headers: { Authorization: "Bearer " + customer.token } });
    }
    expect(profileResponse.status()).toBe(200); const profile = await profileResponse.json(); expect(profile.displayName.length).toBeGreaterThanOrEqual(3);
    const prefix = profile.displayName as string;
    const search = (identity: typeof admin, namePrefix = prefix) => identity.page.request.post(endpoint,{ headers: { Authorization: "Bearer " + identity.token }, data: { namePrefix }, timeout: 30_000 });
    for (const identity of [customer,support,finance]) expect((await search(identity)).status()).toBe(403);
    expect((await admin.page.request.get(endpoint + "?namePrefix=Syn",{ headers: { Authorization: "Bearer " + admin.token } })).status()).toBe(403);
    expect((await search(admin,"ab")).status()).toBe(400);
    const literal = await search(admin,"%%%"); expect(literal.status()).toBe(200); expect((await literal.json()).items).toEqual([]);

    const signedIdentity = await admin.page.request.get("http://localhost:8280/api/v1/admin/operations/me",{headers:{Authorization:"Bearer "+admin.token}});
    expect(signedIdentity.status()).toBe(200);const actor=(await signedIdentity.json()).subject;
    const auditCorrelation=crypto.randomUUID();
    const auditedSearch=await admin.page.request.post(endpoint,{headers:{Authorization:"Bearer "+admin.token,"X-Correlation-ID":auditCorrelation,"X-User-ID":"FORGED_AUDIT_ACTOR"},data:{namePrefix:prefix}});
    expect(auditedSearch.status()).toBe(200);
    const readFacts=async()=>{
      const response=await admin.page.request.get("http://localhost:8280/api/v1/admin/audit/records?eventType=customer.access.recorded",{headers:{Authorization:"Bearer "+admin.token}});
      expect(response.status()).toBe(200);return (await response.json()).items as {actor:string;correlationId:string;details:Record<string,unknown>;sourceTopic:string}[];
    };
    await expect.poll(async()=>(await readFacts()).filter(f=>f.correlationId===auditCorrelation).length,{timeout:90_000,intervals:[1000,2000]}).toBe(1);
    const audit=(await readFacts()).find(f=>f.correlationId===auditCorrelation)!;
    expect(audit.actor).toBe(actor);expect(audit.sourceTopic).toBe("fincore.customer.access.recorded.v1");
    expect(audit.details).toEqual({action:"CUSTOMER_SEARCH",httpMethod:"POST",httpStatus:200,outcome:"RESPONSE_READY",actorProvenance:"SIGNED_JWT"});
    expect(JSON.stringify(audit)).not.toContain(prefix);expect(JSON.stringify(audit)).not.toContain("FORGED_AUDIT_ACTOR");

    await admin.page.getByRole("button",{ name: "Inspection", exact: true }).click();
    const requests: { url: string; method: string; body: string | null }[] = [];
    admin.page.on("request",r => { if (r.url().startsWith(endpoint)) requests.push({url:r.url(),method:r.method(),body:r.postData()}); });
    await admin.page.getByLabel("Name prefix",{ exact: true }).fill(prefix); expect(requests).toHaveLength(0);
    const foundResponse = admin.page.waitForResponse(r => r.url() === endpoint && r.request().method() === "POST");
    await admin.page.getByRole("button",{name:"Search customers",exact:true}).click();
    const found = await foundResponse; expect(found.status()).toBe(200); expect(found.headers()["cache-control"]).toContain("no-store");
    const page = await found.json(); expect(page.items.length).toBeLessThanOrEqual(20);
    const match = page.items.find((item: {customerId:string})=>item.customerId === profile.customerId); expect(match).toBeDefined();
    expect(match.displayName).toBe(profile.displayName); expect(match.profileVersion).toBe(String(profile.version));
    for (const field of ["subject","countryCode","accounts","balance","ownerSubject"]) expect(match).not.toHaveProperty(field);
    const card = admin.page.getByRole("article",{name:"Customer " + profile.customerId,exact:true}); await expect(card.getByText(profile.displayName,{exact:true})).toBeVisible();
    for (const width of [1440,390,320]) {
      await admin.page.setViewportSize({width,height:1000}); expect(await admin.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),"Customer search fits " + width).toBe(true);
      if (process.env.FINCORE_VISUAL_QA === "1" && width !== 320) { mkdirSync(".local/visual",{recursive:true}); await admin.page.screenshot({path:".local/visual/phase-8-customer-search-"+width+".png",fullPage:true,animations:"disabled"}); }
    }
    const inspectionResponse=admin.page.waitForResponse(r=>r.url()==="http://localhost:8280/api/v1/admin/customers/"+profile.customerId && r.request().method()==="GET");
    await card.getByRole("button",{name:"Inspect customer",exact:true}).click();
    const inspected=await inspectionResponse;expect(inspected.status()).toBe(200);const inspectCorrelation=inspected.headers()["x-correlation-id"];
    expect(inspectCorrelation).toMatch(/^[A-Za-z0-9._-]{1,64}$/);
    expect((await inspected.headersArray()).filter(h=>h.name.toLowerCase()==="x-correlation-id")).toHaveLength(1);
    await expect(admin.page.getByRole("region",{name:"Inspected customer",exact:true}).getByText(profile.customerId,{exact:true})).toBeVisible();
    await expect.poll(async()=>(await readFacts()).filter(f=>f.correlationId===inspectCorrelation).length,{timeout:90_000,intervals:[1000,2000]}).toBe(1);
    const inspectionAudit=(await readFacts()).find(f=>f.correlationId===inspectCorrelation)!;expect(inspectionAudit.actor).toBe(actor);
    expect(inspectionAudit.details).toEqual({action:"CUSTOMER_INSPECT",customerId:profile.customerId,httpMethod:"GET",httpStatus:200,outcome:"RESPONSE_READY",actorProvenance:"SIGNED_JWT"});
    expect(JSON.stringify(inspectionAudit)).not.toContain(prefix);
    await expect(admin.page.getByLabel("Resource UUID",{exact:true})).toHaveValue(profile.customerId);
    await admin.page.getByLabel("Name prefix",{exact:true}).fill("Missing-"+crypto.randomUUID()); await expect(admin.page.getByRole("region",{name:"Customer matches",exact:true})).toHaveCount(0);
    await admin.page.getByRole("button",{name:"Search customers",exact:true}).click(); await expect(admin.page.getByText("No customers match this name prefix on this page.",{exact:true})).toBeVisible();
    await admin.page.getByRole("button",{name:"Clear search",exact:true}).click(); await expect(admin.page.getByLabel("Name prefix",{exact:true})).toHaveValue("");
    expect(requests).toHaveLength(2); expect(requests.every(r=>r.url===endpoint && r.method==="POST")).toBe(true); expect(JSON.parse(requests[0].body!).namePrefix).toBe(prefix);
    await finance.page.getByRole("button",{name:"Inspection",exact:true}).click(); await expect(finance.page.getByLabel("Name prefix",{exact:true})).toHaveCount(0);
    await expect(support.page.getByRole("button",{name:"Inspection",exact:true})).toHaveCount(0);
    const after = await customer.page.request.get(profileUrl,{headers:{Authorization:"Bearer "+customer.token}}); expect(after.status()).toBe(200); expect(await after.json()).toEqual(profile);
    console.info("Customer search: real profile, ADMIN-only POST, durable redacted central read fact with signed actor, exact versions, literal wildcard, unchanged profile and 1440/390/320px layouts confirmed.");
  } finally { for (const identity of [customer,admin,support,finance]) await identity.context.close(); }
});
