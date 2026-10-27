import { uuid } from "./api";

export type CardAttempt = { owner: string; key: string; path: string; method: "POST" | "PUT"; body: Record<string, unknown>; title: string; description: string; submitted: boolean };
const slot = (owner: string) => "fincore-card-command:" + owner;
export function readCardAttempt(owner: string): CardAttempt | null {
  try { const value = JSON.parse(sessionStorage.getItem(slot(owner)) ?? "null") as CardAttempt | null;
    return value?.owner === owner && uuid.test(value.key) && /^\/(cards(?:\/[a-f0-9-]{36}(?:\/authorizations)?)?|card-authorizations\/[a-f0-9-]{36}\/settle)$/.test(value.path) && ["POST", "PUT"].includes(value.method) && typeof value.title === "string" && typeof value.description === "string" && typeof value.submitted === "boolean" && value.body !== null && typeof value.body === "object" ? value : null;
  } catch { return null; }
}
export function saveCardAttempt(attempt: CardAttempt) { sessionStorage.setItem(slot(attempt.owner), JSON.stringify(attempt)); }
export function clearCardAttempt(owner: string) { sessionStorage.removeItem(slot(owner)); }
