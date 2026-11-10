import { test, expect } from "@playwright/test";
import { readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";

type Attribute = { key: string; value: { stringValue?: string; intValue?: string } };
type Span = { service: string; traceId: string; spanId: string; parentSpanId?: string; name: string; kind: number; startTimeUnixNano: string; endTimeUnixNano: string; attributes?: Attribute[]; events?: unknown[]; status?: { code?: number; message?: string } };
type Resource = { resource: { attributes: Attribute[] }; scopeSpans: { spans: Omit<Span, "service">[] }[] };
type Receipt = { eventId: string; origin: string; sent: boolean; body: string; outcome: string };
const credentials = JSON.parse(readFileSync(".local/credentials.json", "utf8")) as Record<string, { username: string; password: string }>;
let publisherPaused = false;
const stopped = new Set<"jaeger" | "ledger-service">();
function sql(database: "reporting" | "audit", query: string) {
  const r = spawnSync("docker", ["compose", "exec", "-T", "postgres", "sh", "-c", 'PGPASSWORD="$POSTGRES_PASSWORD" psql -X -v ON_ERROR_STOP=1 -U fincore_bootstrap -d ' + database + " -At"], { input: query, encoding: "utf8", timeout: 30_000 });
  expect(r.status, "Private trace verification query succeeded").toBe(0); return r.stdout.trim();
}
function container(service: "jaeger" | "ledger-service") {
  const r = spawnSync("docker", ["compose", "ps", "--all", "--quiet", service], { encoding: "utf8", timeout: 30_000 });
  expect(r.status).toBe(0); const id = r.stdout.trim(); expect(id).toMatch(/^[0-9a-f]{12,64}$/); return id;
}
function stop(service: "jaeger" | "ledger-service") { stopped.add(service); expect(spawnSync("docker", ["stop", container(service)], { timeout: 60_000 }).status).toBe(0); }
function start(service: "jaeger" | "ledger-service") { expect(spawnSync("docker", ["start", container(service)], { timeout: 60_000 }).status).toBe(0); stopped.delete(service); }
async function collectorReady() { await expect.poll(async () => { try { return (await fetch("http://localhost:16686/api/v3/services", { signal: AbortSignal.timeout(3000) })).status; } catch { return 0; } }, { timeout: 90_000 }).toBe(200); }
async function ledgerReady() { await expect.poll(() => spawnSync("docker", ["inspect", container("ledger-service"), "--format", "{{.State.Health.Status}}"], { encoding: "utf8", timeout: 10_000 }).stdout.trim(), { timeout: 90_000 }).toBe("healthy"); }
function receipt(correlation: string): Receipt | null {
  expect(correlation).toMatch(/^[0-9a-f-]{36}$/);
  const row = sql("reporting", "select json_build_object('eventId',r.id,'origin',d.traceparent,'sent',d.sent_at is not null,'body',s.envelope::text,'outcome',r.outcome)::text from reporting_access_audit r join fincore_event_source s on s.event_id=r.id left join event_delivery d on d.event_id=r.id where r.correlation_id='" + correlation + "';");
  return row ? JSON.parse(row) as Receipt : null;
}
function centralHash(eventId: string) { expect(eventId).toMatch(/^[0-9a-f-]{36}$/); return sql("audit", "select body_hash from audit_record where id='" + eventId + "';"); }
const hash = (body: string) => createHash("sha256").update(body).digest("hex");
async function graph(trace: string): Promise<Span[]> {
  expect(trace).toMatch(/^[0-9a-f]{32}$/);
  // Inspect stored OTLP data before Jaeger's UI clock-skew adjuster adds synthetic warning attributes.
  const r = await fetch("http://localhost:16686/api/v3/traces/" + trace + "?rawTraces=true", { signal: AbortSignal.timeout(5000) }); if (r.status === 404) return []; expect(r.status).toBe(200);
  const chunks = (await r.text()).trim().split(/\n/).filter(Boolean).map(line => JSON.parse(line) as { result: { resourceSpans: Resource[] } });
  return chunks.flatMap(c => c.result.resourceSpans.flatMap(resource => {
    const service = resource.resource.attributes.find(a => a.key === "service.name")?.value.stringValue ?? "missing";
    expect(resource.resource.attributes.map(a => a.key)).toEqual(["service.name"]);
    return resource.scopeSpans.flatMap(scope => scope.spans.map(span => ({ ...span, service })));
  }));
}
function privacy(spans: Span[], privateStrings: string[]) {
  const serialized = JSON.stringify(spans);
  for (const value of privateStrings) expect(serialized.includes(value), "Collected spans exclude private input").toBe(false);
  for (const span of spans) {
    expect(["http.server", "http.client", "reporting.access.publish", "audit.receive"].includes(span.name)).toBe(true);
    expect((span.attributes ?? []).every(a => ["http.request.method", "http.response.status_code", "fincore.dependency"].includes(a.key))).toBe(true);
    expect(span.events ?? []).toHaveLength(0); expect(span.status?.message ?? "").toBe("");
  }
}
function complete(spans: Span[]) { return ["api-gateway", "reporting-service", "ledger-service", "audit-service"].every(service => spans.some(s => s.service === service)); }
function assertGraph(spans: Span[]) {
  const find = (service: string, name: string) => { const matches = spans.filter(s => s.service === service && s.name === name); expect(matches).toHaveLength(1); return matches[0]; };
  const gateway = find("api-gateway", "http.server"), reporting = find("reporting-service", "http.server"), ledger = find("ledger-service", "http.server"), producer = find("reporting-service", "reporting.access.publish"), consumer = find("audit-service", "audit.receive");
  const ledgerClient = spans.find(s => s.service === "reporting-service" && s.name === "http.client" && s.attributes?.some(a => a.key === "fincore.dependency" && a.value.stringValue === "LEDGER")); expect(ledgerClient).toBeDefined();
  expect(reporting.parentSpanId).toBe(gateway.spanId); expect(ledgerClient!.parentSpanId).toBe(reporting.spanId); expect(ledger.parentSpanId).toBe(ledgerClient!.spanId);
  expect(producer.parentSpanId).toBe(reporting.spanId); expect(consumer.parentSpanId).toBe(producer.spanId);
  expect(BigInt(producer.startTimeUnixNano) >= BigInt(reporting.endTimeUnixNano), "Publication starts after the collected owner request ended").toBe(true);
}
test.afterEach(async () => {
  try { if (publisherPaused) { sql("reporting", "grant select on fincore_event_source to reporting_runtime;"); publisherPaused = false; } }
  finally { try { if (stopped.has("ledger-service")) { start("ledger-service"); await ledgerReady(); } } finally { if (stopped.has("jaeger")) { start("jaeger"); await collectorReady(); } } }
});

test("collected Reporting traces preserve delayed outbox parentage and survive collector and Ledger outages", async ({ browser }) => {
  test.setTimeout(420_000);
  const context = await browser.newContext(), page = await context.newPage();
  await page.goto("http://localhost:5174/"); await page.getByRole("button", { name: "Sign in securely", exact: true }).click(); await expect(page).toHaveURL(/localhost:8180/);
  expect(new URL(page.url()).searchParams.get("code_challenge_method")).toBe("S256");
  await page.getByLabel("Username or email").fill(credentials.finance.username); await page.getByLabel("Password", { exact: true }).fill(credentials.finance.password);
  const grant = page.waitForResponse(r => r.url().endsWith("/protocol/openid-connect/token") && r.request().method() === "POST");
  await page.getByRole("button", { name: "Sign In", exact: true }).click(); const auth = await grant; expect(auth.status()).toBe(200); const token = (await auth.json()).access_token as string;
  const subject = JSON.parse(Buffer.from(token.split(".")[1], "base64url").toString()).sub as string;
  const privateStrings = [token, subject, credentials.finance.username, credentials.finance.password, "PRIVATE_QUERY_CANARY", "PRIVATE_BAGGAGE_CANARY", "PRIVATE_STATE_CANARY"];
  const call = async (expected = 200, parent?: string) => {
    const correlation = randomUUID(); const response = await page.request.get("http://localhost:8280/api/v1/admin/reports/reconciliation?privateTraceCanary=PRIVATE_QUERY_CANARY", { headers: { Authorization: "Bearer " + token, "X-Correlation-ID": correlation, baggage: "name=PRIVATE_BAGGAGE_CANARY", tracestate: "vendor=PRIVATE_STATE_CANARY", ...(parent ? { traceparent: parent } : {}) }, timeout: 45_000 });
    expect(response.status()).toBe(expected); expect(response.headers()["x-correlation-id"]).toBe(correlation); const trace = response.headers()["x-trace-id"]; expect(trace).toMatch(/^[0-9a-f]{32}$/); return { trace, correlation };
  };
  await collectorReady(); publisherPaused = true; sql("reporting", "revoke select on fincore_event_source from reporting_runtime;");
  const original = await call();
  await expect.poll(async () => (await graph(original.trace)).some(s => s.service === "reporting-service" && s.name === "http.server"), { timeout: 45_000 }).toBe(true);
  const prepared = receipt(original.correlation)!; expect(prepared).not.toBeNull(); expect(prepared.sent).toBe(false); expect(prepared.outcome).toBe("RESPONSE_READY"); expect(centralHash(prepared.eventId)).toBe("");
  const before = await graph(original.trace); privacy(before, privateStrings); const owner = before.find(s => s.service === "reporting-service" && s.name === "http.server")!;
  expect(prepared.origin).toBe("00-" + original.trace + "-" + owner.spanId + "-01");
  sql("reporting", "grant select on fincore_event_source to reporting_runtime;"); publisherPaused = false;
  await expect.poll(async () => complete(await graph(original.trace)), { timeout: 90_000 }).toBe(true);
  const collected = await graph(original.trace); privacy(collected, privateStrings); assertGraph(collected);
  await expect.poll(() => centralHash(prepared.eventId), { timeout: 60_000 }).toBe(hash(prepared.body)); expect(receipt(original.correlation)!.body).toBe(prepared.body);
  writeFileSync(".local/reporting-trace-native-graph.json", JSON.stringify({ checkedAt: new Date().toISOString(), trace: original.trace, spans: collected, delayedPublication: true, bodyHashPreserved: true }, null, 2));
  console.info("PASS: stored Gateway/Reporting/Ledger and delayed Kafka/Audit trace graph; immutable body digest and redaction confirmed.");

  stop("jaeger"); const unavailable = await call(); const retained = receipt(unavailable.correlation)!; expect(retained).not.toBeNull();
  await expect.poll(() => centralHash(retained.eventId), { timeout: 60_000 }).toBe(hash(retained.body));
  start("jaeger"); await collectorReady();
  console.info("PASS: collector outage leaves the HTTP response and durable central receipt working; collector restarted.");

  // Query readiness does not prove that the application exporters have reconnected.
  // A best-effort batch lost during restart cannot be made observable by polling its old ID.
  await expect(async () => {
    const candidate = await call();
    await expect.poll(async () => complete(await graph(candidate.trace)), { timeout: 5000, intervals: [250, 500, 1000] }).toBe(true);
    const received = await graph(candidate.trace); privacy(received, privateStrings);
    const retainedCandidate = receipt(candidate.correlation)!; expect(retainedCandidate).not.toBeNull();
    await expect.poll(() => centralHash(retainedCandidate.eventId), { timeout: 5000 }).toBe(hash(retainedCandidate.body));
  }).toPass({ timeout: 90_000, intervals: [1000, 2000] });
  console.info("PASS: actual stored graph confirms all application exporters recovered after collector restart.");

  stop("ledger-service"); const failed = await call(503);
  const failedReceipt = receipt(failed.correlation)!; expect(failedReceipt).not.toBeNull(); expect(failedReceipt.outcome).toBe("SERVER_ERROR"); expect(failedReceipt.origin).toContain(failed.trace);
  await expect.poll(async () => (await graph(failed.trace)).filter(s => s.service === "reporting-service" && s.name === "http.client").map(s => ({ dependency: s.attributes?.find(a => a.key === "fincore.dependency")?.value.stringValue, status: s.status?.code })), { timeout: 45_000 }).toContainEqual({ dependency: "LEDGER", status: 2 });
  privacy(await graph(failed.trace), privateStrings); expect(receipt(failed.correlation)!.outcome).toBe("SERVER_ERROR");
  start("ledger-service"); await ledgerReady();
  const untrusted = "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01";
  const recovery = await call(200, untrusted + "," + untrusted); expect(recovery.trace).not.toBe("a".repeat(32));
  await expect.poll(async () => complete(await graph(recovery.trace)), { timeout: 90_000 }).toBe(true);
  privacy(await graph(recovery.trace), privateStrings);
  console.info("PASS: collected dependency failure and recovery, rejected ambiguous trace context and restored native services.");
  await context.close();
});
