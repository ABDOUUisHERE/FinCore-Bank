import { uuid } from "../banking/api";

export type IncidentAttempt = { owner: string; key: string; path: string; method: "POST" | "PUT"; body: Record<string, unknown>; title: string; description: string; submitted: boolean };
const slot = (owner: string) => "fincore-incident-command:" + owner;
export function readIncidentAttempt(owner: string): IncidentAttempt | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(slot(owner)) ?? "null") as IncidentAttempt | null;
    return value?.owner === owner && uuid.test(value.key) &&
      (value.method === "POST" && value.path === "/admin/incidents" || value.method === "PUT" && /^\/admin\/incidents\/[a-f0-9-]{36}\/(status|account-access|payment-access|customer-access)$/i.test(value.path)) &&
      typeof value.title === "string" && typeof value.description === "string" && typeof value.submitted === "boolean" &&
      value.body !== null && typeof value.body === "object" && !Array.isArray(value.body) ? value : null;
  } catch { return null; }
}
export function saveIncidentAttempt(attempt: IncidentAttempt) { sessionStorage.setItem(slot(attempt.owner), JSON.stringify(attempt)); }
export function clearIncidentAttempt(owner: string) { sessionStorage.removeItem(slot(owner)); }
