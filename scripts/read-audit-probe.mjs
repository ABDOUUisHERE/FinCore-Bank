/** Compose acceptance probe. Backend audit behavior lives in Java/Spring Boot. No credentials or raw events are printed. */
import assert from "node:assert/strict";
import console from "node:console";

import { spawnSync } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { setTimeout as delay } from "node:timers/promises";

function compose(args, input) {
  return spawnSync("docker", ["compose", ...args], { input, encoding: "utf8", timeout: 90_000, maxBuffer: 2 * 1024 * 1024 });
}
function sql(database, statement) {
  assert(["customer", "account", "payment", "audit"].includes(database));
  const result = compose(["exec", "-T", "postgres", "sh", "-c",
    'PGPASSWORD="$POSTGRES_PASSWORD" psql -X -v ON_ERROR_STOP=1 -U fincore_bootstrap -d ' + database + " -At"], statement);
  assert.equal(result.status, 0, "Private database verification failed");
  return result.stdout.trim();
}
async function until(read, accepts, label) {
  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline) {
    const value = read();
    if (accepts(value)) return value;
    await delay(1000);
  }
  throw new Error(label);
}
function direct(correlation, route) {
  const result = compose(["exec", "-T", "api-gateway", "wget", "-S", "-O", "-", "--header", "X-Correlation-ID: " + correlation,
    "http://" + route.domain + "-service:8080" + route.path]);
  return result.stdout + result.stderr;
}
function publish(domain, key, body) {
  const result = compose(["exec", "-T", "-e", "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m", "kafka", "/opt/kafka/bin/kafka-console-producer.sh",
    "--bootstrap-server", "kafka:9092", "--producer.config", "/etc/kafka/secrets/" + domain + ".properties", "--topic", "fincore." + domain + ".access.recorded.v1",
    "--sync", "--property", "parse.key=true", "--property", "key.separator=|", "--producer-property", "max.block.ms=10000",
    "--producer-property", "request.timeout.ms=5000", "--producer-property", "delivery.timeout.ms=12000"], key + "|" + body + "\n");
  assert.equal(result.status, 0, "Customer access broker publication failed");
}
const hash = body => createHash("sha256").update(body).digest("hex");
export async function verifyReadAudit(domain, exerciseStorageFailure = false) {
  assert(["customer", "account", "payment"].includes(domain));
  const reference = domain === "customer" ? null : randomUUID();
  const action = domain === "customer" ? "CUSTOMER_SEARCH" : domain.toUpperCase() + "_INSPECT";
  const route = { domain, path: domain === "customer" ? "/api/v1/admin/customers/search" : "/api/v1/admin/" + (domain === "account" ? "accounts/" : "transactions/") + reference };
const correlation = randomUUID();
assert.match(direct(correlation, route), /HTTP\/1\.[01] 401(?:\s|$)/, "Direct owner credentials must be independently enforced");
const local = JSON.parse(sql(domain, "select row_to_json(a)::text from " + domain + "_access_audit a where correlation_id='" + correlation + "';"));
assert.equal(local.action, action); assert.equal(local.http_status, 401); assert.equal(local.outcome, "UNAUTHENTICATED");
assert.equal(local.actor_subject, null); assert.equal(local.actor_provenance, "UNAVAILABLE"); assert.equal(local[domain + "_id"], reference);
const raw = sql(domain, "select envelope::text from fincore_event_source where event_id='" + local.id + "';");
const retained = await until(() => sql("audit", "select count(*) from audit_record where id='" + local.id + "';"), v => v === "1", "Owner receipt was not delivered centrally");
assert.equal(retained, "1");
const fact = JSON.parse(sql("audit", "select to_jsonb(r)::text from audit_record r where id='" + local.id + "';"));
assert.equal(fact.actor, null); assert.equal(fact.body_hash, hash(raw)); assert.equal(fact.source_topic, "fincore." + domain + ".access.recorded.v1");
assert(Number.isInteger(fact.source_partition)); assert.notEqual(fact.source_offset, null);
assert.deepEqual(fact.details, { action: action, httpMethod: "GET", httpStatus: 401, outcome: "UNAUTHENTICATED", actorProvenance: "UNAVAILABLE", ...(reference ? { [domain + "Id"]: reference } : {}) });
publish(domain, local.id, raw); // Byte-identical duplicate, then a conflicting same-key record in the same partition.
const conflict = JSON.stringify({ ...JSON.parse(raw), privateCanary: "PRIVATE_AUDIT_CANARY" });
publish(domain, local.id, conflict);
await until(() => sql("audit", "select count(*) from audit_quarantine where body_hash='" + hash(conflict) + "' and code='EVENT_IDENTITY_CONFLICT';"), v => Number(v) >= 1, "Conflicting replay was not quarantined");
assert.equal(sql("audit", "select count(*) from audit_record where id='" + local.id + "' and body_hash='" + hash(raw) + "';"), "1");
const invalidId = randomUUID();
const invalid = JSON.stringify({ ...JSON.parse(raw), eventId: invalidId, aggregateId: invalidId,
  payload: { ...JSON.parse(raw).payload, action: "PRIVATE_INVALID_ACTION", namePrefix: "PRIVATE_AUDIT_CANARY" } });
publish(domain, invalidId, invalid);
await until(() => sql("audit", "select count(*) from audit_quarantine where body_hash='" + hash(invalid) + "' and code='INVALID_ACCESS_AUDIT';"), v => Number(v) >= 1, "Invalid access metadata was not quarantined");
assert.equal(sql("audit", "select count(*) from audit_record where id='" + invalidId + "';"), "0");
assert.equal(sql("audit", "select count(*) from audit_quarantine q where body_hash in ('" + hash(conflict) + "','" + hash(invalid) + "') and to_jsonb(q)::text like '%PRIVATE_AUDIT_CANARY%';"), "0");
console.info("PASS: " + domain + " direct owner denial, durable outbox delivery, central redaction, identical replay and conflicting/invalid record quarantine");

if (exerciseStorageFailure) {
  assert.equal(sql(domain, "select has_table_privilege('" + domain + "_runtime','" + domain + "_access_audit','INSERT');"), "t");
  const unavailable = randomUUID();
  try {
    sql(domain, "revoke insert on " + domain + "_access_audit from " + domain + "_runtime;");
    assert.match(direct(unavailable, route), /HTTP\/1\.[01] 503(?:\s|$)/, "An unretained denial must be replaced with an unavailable response");
    assert.equal(sql(domain, "select count(*) from " + domain + "_access_audit where correlation_id='" + unavailable + "';"), "0");
  } finally {
    sql(domain, "grant insert on " + domain + "_access_audit to " + domain + "_runtime;");
  }
  const recovered = randomUUID(); assert.match(direct(recovered, route), /HTTP\/1\.[01] 401(?:\s|$)/);
  assert.equal(sql(domain, "select count(*) from " + domain + "_access_audit where correlation_id='" + recovered + "';"), "1");
  console.info("PASS: " + domain + " unavailable owner audit storage returns 503 without claiming retention; runtime INSERT privilege restored and recovery confirmed");
}

}

export function verifyReadAuditProducerIsolation() {
  for (const [domain, foreign] of [["account", "payment"], ["payment", "account"]]) {
    const result = compose(["exec", "-T", "-e", "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m", "kafka", "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server", "kafka:9092", "--producer.config", "/etc/kafka/secrets/" + domain + ".properties", "--topic", "fincore." + foreign + ".access.recorded.v1", "--sync", "--producer-property", "max.block.ms=10000", "--producer-property", "request.timeout.ms=5000", "--producer-property", "delivery.timeout.ms=12000"], "{\"mustNeverPublish\":true}\n");
    assert.match(result.stderr + result.stdout, /TopicAuthorizationException/, "Owner producer must be denied foreign receipts");
  }
  console.info("PASS: Account and Payment producer identities cannot forge each other's access receipts");
}
