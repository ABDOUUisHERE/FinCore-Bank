import process from "node:process";
import { verifyReadAudit, verifyReadAuditProducerIsolation } from "./read-audit-probe.mjs";
for (const domain of ["customer", "account", "payment"]) await verifyReadAudit(domain, process.argv.includes("--exercise-storage-failure"));
verifyReadAuditProducerIsolation();
