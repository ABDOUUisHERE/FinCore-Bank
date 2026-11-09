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

test("support discovers only incident-authorized customers and revocation withholds names and profile", async ({ browser }) => {
  test.setTimeout(300_000);
  const customer = await login(browser,"customer",false), admin = await login(browser,"admin"), support = await login(browser,"support"), other = await login(browser,"support-second");
  let base = "";
  const call = async (identity: typeof admin, method: string, path: string, body?: object, key?: string, expected = 200) => {
    const response = await identity.page.request.fetch("http://localhost:8280/api/v1"+path,{method,headers:{Authorization:"Bearer "+identity.token,...(key?{"Idempotency-Key":key}:{})},...(body?{data:body}:{}),timeout:35_000});
    expect(response.status(),"Scoped customer request "+path).toBe(expected);return response.json();
  };
  try {
    const own=await customer.page.request.get("http://localhost:8280/api/v1/customers/me/profile",{headers:{Authorization:"Bearer "+customer.token}});
    if(own.status()===404)await call(customer,"POST","/customers/me/onboarding",{displayName:"Scoped Synthetic Customer",countryCode:"MA"});else expect(own.status()).toBe(200);
    const profile=await call(customer,"GET","/customers/me/profile"),prefix=profile.displayName as string;
    const incident=await call(support,"POST","/admin/incidents",{resourceKind:"CUSTOMER",resourceId:profile.customerId,severity:"MEDIUM",reason:"CUSTOMER_REPORTED"},crypto.randomUUID());base="/admin/incidents/"+incident.incidentId;
    const search="/admin/support/customers/search";
    await call(support,"POST",base+"/customer-profile",{},undefined,403);await call(support,"POST",search,{namePrefix:prefix},undefined,403);
    for(const identity of [customer,admin])await call(identity,"POST",search,{namePrefix:prefix},undefined,403);
    await call(support,"POST",search,{namePrefix:prefix,customerIds:[profile.customerId]},undefined,400);
    await call(other,"GET",base+"/customer-access",undefined,undefined,404);
    const openIncident=async(identity:typeof admin)=>{
      await identity.page.getByRole("button",{name:"Incidents",exact:true}).click();await identity.page.getByRole("button",{name:"Refresh incidents",exact:true}).click();
      const target=identity.page.getByRole("button",{name:"Open incident "+incident.incidentId.slice(-8),exact:true});
      for(let n=0;n<10;n++){if(await target.count())break;const more=identity.page.getByRole("button",{name:"Load more incidents",exact:true});if(await more.count())await more.click();else await identity.page.waitForTimeout(300);}
      await target.click();await expect(identity.page.getByRole("region",{name:"Temporary customer access",exact:true})).toBeVisible();
    };
    await openIncident(admin);
    const commands:{key:string;body:string|null}[]=[];let receipt:unknown;
    await admin.page.route("**/api/v1"+base+"/customer-access",async route=>{
      if(route.request().method()!=="PUT"){await route.continue();return;}
      const n=commands.push({key:route.request().headers()["idempotency-key"],body:route.request().postData()}),response=await route.fetch();expect(response.status()).toBe(200);
      const result=await response.json();if(n===1){receipt=result;await route.abort("failed");}else{expect(result).toEqual(receipt);await route.fulfill({response});}
    });
    await admin.page.getByRole("button",{name:"Review temporary access",exact:true}).click();expect(commands).toHaveLength(0);
    await admin.page.getByRole("button",{name:"Confirm incident request",exact:true}).click();await expect(admin.page.getByRole("button",{name:"Retry same incident request",exact:true})).toBeVisible();
    await admin.page.reload();await admin.page.getByRole("button",{name:"Incidents",exact:true}).click();await admin.page.getByRole("button",{name:"Retry same incident request",exact:true}).click();
    await expect(admin.page.getByText("Recorded access result: active at version 1. Current permission is refreshed below.")).toBeVisible();expect(commands).toHaveLength(2);expect(commands[1]).toEqual(commands[0]);await admin.page.unroute("**/api/v1"+base+"/customer-access");
    await support.page.getByRole("button",{name:"Incidents",exact:true}).click();
    const discovery=support.page.getByRole("region",{name:"Authorized customer discovery",exact:true});
    const searches:{url:string;body:string|null}[]=[];support.page.on("request",r=>{if(r.url().endsWith(search))searches.push({url:r.url(),body:r.postData()});});
    await discovery.getByLabel("Authorized customer name prefix",{exact:true}).fill(prefix);expect(searches).toHaveLength(0);
    const searched=support.page.waitForResponse(r=>r.url().endsWith(search)&&r.request().method()==="POST");await discovery.getByRole("button",{name:"Search authorized customers",exact:true}).click();
    const response=await searched;expect(response.status()).toBe(200);expect(response.headers()["cache-control"]).toContain("no-store");const found=await response.json();
    expect(found.items).toEqual([{customerId:profile.customerId,displayName:profile.displayName,status:"ACTIVE",incidentId:incident.incidentId}]);expect(found.hasMore).toBe(false);
    await expect(discovery.getByRole("region",{name:"Authorized customer results",exact:true})).toBeVisible();expect(searches).toHaveLength(1);expect(searches[0].url).not.toContain(encodeURIComponent(prefix));expect(JSON.parse(searches[0].body!)).toEqual({namePrefix:prefix});
    expect(await support.page.evaluate(name=>Object.values(sessionStorage).some(value=>value.includes(name)),prefix)).toBe(false);
    for(const width of [1440,390,320]){await support.page.setViewportSize({width,height:1000});expect(await support.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),"Scoped discovery fits "+width).toBe(true);}
    await discovery.getByRole("button",{name:"Open authorized customer incident",exact:true}).click();const panel=support.page.getByRole("region",{name:"Temporary customer access",exact:true});
    const observed=support.page.waitForResponse(r=>r.url().endsWith(base+"/customer-profile"));await panel.getByRole("button",{name:"Consult scoped customer profile",exact:true}).click();const observedResponse=await observed;expect(observedResponse.status()).toBe(200);const view=await observedResponse.json();
    expect(Object.keys(view).sort()).toEqual(["createdAt","customerId","displayName","observedAt","profileVersion","status"]);expect(view.customerId).toBe(profile.customerId);expect(view.profileVersion).toBe(String(profile.version));
    await expect(panel.getByRole("region",{name:"Scoped customer observation",exact:true})).toBeVisible();
    for(const width of [1440,390,320]){await support.page.setViewportSize({width,height:1000});expect(await support.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),"Scoped profile fits "+width).toBe(true);if(process.env.FINCORE_VISUAL_QA==="1"&&width!==320){mkdirSync(".local/visual",{recursive:true});await panel.screenshot({path:".local/visual/phase-8-support-customer-"+width+".png",animations:"disabled"});}}
    await call(other,"POST",search,{namePrefix:prefix},undefined,403);await call(other,"POST",base+"/customer-profile",{},undefined,403);await call(support,"POST","/admin/customers/search",{namePrefix:prefix},undefined,403);
    await openIncident(admin);await admin.page.getByRole("button",{name:"Review access revocation",exact:true}).click();await admin.page.getByRole("button",{name:"Confirm incident request",exact:true}).click();await expect(admin.page.getByText("Recorded access result: revoked at version 2. Current permission is refreshed below.")).toBeVisible();
    await panel.getByRole("button",{name:"Consult scoped customer profile",exact:true}).click();await expect(panel.getByRole("alert")).toBeVisible();await expect(panel.getByRole("region",{name:"Scoped customer observation",exact:true})).toHaveCount(0);
    await discovery.getByRole("button",{name:"Search authorized customers",exact:true}).click();await expect(discovery.getByRole("alert")).toBeVisible();await expect(discovery.getByRole("region",{name:"Authorized customer results",exact:true})).toHaveCount(0);
    await call(admin,"PUT",base+"/customer-access",{expectedVersion:"2",action:"ISSUE",minutes:5,reason:"CONTROL_REVIEW"},crypto.randomUUID());
    await call(support,"PUT",base+"/status",{expectedVersion:0,status:"INVESTIGATING",reason:"INVESTIGATION_UPDATE"},crypto.randomUUID());await call(support,"PUT",base+"/status",{expectedVersion:1,status:"RESOLVED",reason:"ISSUE_RESOLVED"},crypto.randomUUID());await call(support,"PUT",base+"/status",{expectedVersion:2,status:"INVESTIGATING",reason:"REOPENED_FOR_REVIEW"},crypto.randomUUID());
    await call(support,"POST",search,{namePrefix:prefix},undefined,403);await call(support,"POST",base+"/customer-profile",{},undefined,403);
    expect(await call(admin,"PUT",base+"/customer-access",JSON.parse(commands[0].body!),commands[0].key)).toEqual(receipt);expect((await call(admin,"GET",base+"/customer-access")).state).toBe("REVOKED");
    const facts=await call(admin,"GET","/admin/audit/records?eventType=support.customer.search");expect(facts.items.some((f:{details:{outcome:string}})=>f.details.outcome==="DISCLOSED")).toBe(true);expect(JSON.stringify(facts)).not.toContain(prefix);
    expect(await call(customer,"GET","/customers/me/profile")).toEqual(profile);
    console.info("Scoped Customer: hosted PKCE, server-authorized literal discovery, six-field profile, exact receipt recovery, foreign/no-grant denial, revoke masking, resolution/reopen denial, unchanged profile and responsive views confirmed.");
  } finally {
    if(base){const current=await call(admin,"GET",base+"/customer-access");if(current.state==="ACTIVE")await call(admin,"PUT",base+"/customer-access",{expectedVersion:current.version,action:"REVOKE",reason:"ACCESS_NO_LONGER_REQUIRED"},crypto.randomUUID());}
    for(const identity of [customer,admin,support,other])await identity.context.close();
  }
});
