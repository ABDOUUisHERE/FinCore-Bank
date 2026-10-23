import { type TransferBody, uuid } from "./api";
import { amount } from "./money";
export type Attempt = { owner: string; key: string; body: TransferBody; submitted: boolean; paymentId?: string };
const name = (owner: string) => "fincore.transfer." + owner;
export function readAttempt(owner: string): Attempt | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(name(owner)) ?? "null") as Attempt | null;
    if (!value) return null;
    if (value.owner !== owner || !uuid.test(value.key) || typeof value.submitted !== "boolean" ||
      !uuid.test(value.body.sourceAccountId) || !uuid.test(value.body.destinationAccountId) ||
      value.body.sourceAccountId === value.body.destinationAccountId || !["MAD", "EUR", "USD"].includes(value.body.currency) ||
      typeof value.body.reference !== "string" || value.body.reference.length > 140 ||
      amount(value.body.amount) !== value.body.amount || (value.paymentId !== undefined && !uuid.test(value.paymentId))) return null;
    return value;
  } catch { return null; }
}
export function saveAttempt(value: Attempt) {
  try { sessionStorage.setItem(name(value.owner), JSON.stringify(value)); }
  catch { throw new Error("We could not save this transfer safely. Allow browser session storage and try again."); }
}
export function clearAttempt(owner: string) { sessionStorage.removeItem(name(owner)); }
