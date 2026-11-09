import process from "node:process";
import { verifyReadAudit } from "./read-audit-probe.mjs";
// Preserve the lot 8 Customer-only operator entry point.
await verifyReadAudit("customer", process.argv.includes("--exercise-storage-failure"));
