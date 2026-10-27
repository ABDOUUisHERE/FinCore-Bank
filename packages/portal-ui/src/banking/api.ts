export type Currency = "MAD" | "EUR" | "USD";
export type Page<T> = { items: T[]; nextCursor: string | null };
export type Profile = { customerId: string; subject: string; displayName: string; countryCode: string; status: string; version: number };
export type Account = { accountId: string; customerId: string; currency: Currency; status: string; version: number; pendingStatus: string | null; guardError: string | null };
export type Balance = { booked: string; reserved?: string; available: string; currency: Currency; version: number };
export type Beneficiary = { beneficiaryId: string; destinationAccountId: string; label: string; currency: Currency; status: "ACTIVE" | "DISABLED"; version: number };
export type TransferBody = { sourceAccountId: string; destinationAccountId: string; amount: string; currency: Currency; reference: string };
export type Payment = { paymentId: string; kind?: string; status: string; version: number; amount: string; currency: Currency; reference?: string; journalId?: string | null; code?: string | null; sourceAccountId?: string; destinationAccountId?: string; createdAt?: string };
export type Entry = { journalId: string; operationId: string; currency: Currency; side: "DEBIT" | "CREDIT"; amount: string; createdAt: string; reversalOf: string | null; reversedBy: string | null; kind: string; reference: string; paymentId: string | null; sourceAccountId: string | null };
export type Activity = { month: string; days: { date: string; incoming: string; outgoing: string }[] };
export const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const messages: Record<string, string> = {
  INSUFFICIENT_FUNDS: "There are not enough available funds.",
  ACCOUNT_NOT_EMPTY: "This account must have a zero balance and no live reservations before it can be closed.",
  CARD_HAS_RESERVATIONS: "Release live reservations before terminating this card.",
  CARD_VERSION_CONFLICT: "The card controls changed. Refresh the card before trying again.",
  CARD_LIMIT_EXCEEDED: "This authorization exceeds the card payment or UTC daily limit.",
  CARD_RESTRICTED: "The card or its account does not allow new authorizations.",
  REPORT_TOO_LARGE: "This month exceeds the 1,000-entry export limit. No truncated statement was saved.",
  ACCOUNT_VERSION_CONFLICT: "This account changed. Refresh its latest status before trying again.",
  BENEFICIARY_VERSION_CONFLICT: "This beneficiary changed. Refresh the list before making another change.",
  PROFILE_VERSION_CONFLICT: "Your profile changed. Refresh it before saving again.",
  PAYMENT_STATE_CONFLICT: "The transfer changed or execution has started. Refresh its latest status.",
  IDEMPOTENCY_CONFLICT: "This request conflicts with an earlier submission. Keep its reference and check the transfer list.",
  BENEFICIARY_EXISTS: "This account is already saved. You can enable it again in your beneficiaries.",
  RECIPIENT_UNAVAILABLE: "This recipient account is not currently available.",
  DEMO_HARD_LIMIT: "This amount exceeds the simulation limit.",
  CANCELLED: "You cancelled this transfer before execution.",
};
export class ApiError extends Error {
  constructor(public status: number, public code = "", public correlation = "") {
    super(messages[code] ?? (status === 401 ? "Your session has expired. Sign in again." :
      status === 403 ? "Your identity cannot perform this action." :
      status === 404 ? "This resource is unavailable to your identity." :
      status === 409 ? "The resource changed. Refresh its latest state." :
      status === 422 ? "This operation is not allowed for the current account or currency." :
      status === 400 ? "Check the entered details and try again." :
      "We could not confirm a response. Check the connection and try again."));
  }
}
export function reason(code?: string | null) { return code ? messages[code] ?? code.replaceAll("_", " ").toLowerCase() : ""; }
export function query(values: Record<string, string | undefined>) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) if (value) params.set(key, value);
  return params.size ? "?" + params.toString() : "";
}
export async function request<T>(token: () => Promise<string>, path: string, options: { method?: string; body?: unknown; key?: string; signal?: AbortSignal } = {}): Promise<T> {
  const bearer = await token();
  // New delegated Card/Reporting routes allow a bounded chain of 5s owner calls.
  const delegated = ["/cards", "/card-authorizations", "/card-merchants", "/statements", "/admin/reports"].some(prefix => path === prefix || path.startsWith(prefix + "/") || path.startsWith(prefix + "?"));
  const signals = [AbortSignal.timeout(delegated ? 25_000 : 15_000), ...(options.signal ? [options.signal] : [])];
  let response: Response;
  try {
    response = await fetch((import.meta.env.VITE_API_URL ?? "http://localhost:8280") + "/api/v1" + path, {
      method: options.method ?? "GET", cache: "no-store", credentials: "omit",
      headers: { Authorization: "Bearer " + bearer, "Content-Type": "application/json",
        "X-Correlation-ID": crypto.randomUUID(), ...(options.key ? { "Idempotency-Key": options.key } : {}) },
      body: options.body === undefined ? undefined : JSON.stringify(options.body), signal: AbortSignal.any(signals),
    });
  } catch { throw new ApiError(0); }
  let data: unknown;
  try { data = await response.json(); } catch { throw new ApiError(response.ok ? 502 : response.status); }
  if (!response.ok) {
    const code = data && typeof data === "object" && "code" in data && typeof data.code === "string" && /^[A-Z_]{1,80}$/.test(data.code) ? data.code : "";
    throw new ApiError(response.status, code, response.headers.get("X-Correlation-ID") ?? "");
  }
  return data as T;
}
export async function profile(token: () => Promise<string>, signal?: AbortSignal) {
  try { return await request<Profile>(token, "/customers/me/profile", { signal }); }
  catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error; }
}
export async function page<T>(token: () => Promise<string>, path: string, signal?: AbortSignal) {
  const result = await request<Page<T>>(token, path, { signal });
  if (!Array.isArray(result.items) || (result.nextCursor !== null && typeof result.nextCursor !== "string")) throw new ApiError(502);
  return result;
}
export const pending = (status: string) => ["CREATED", "VALIDATING", "PROCESSING", "PENDING_RISK_REVIEW", "REVERSAL_PENDING"].includes(status);
export const posted = (payment: Payment) => payment.status === "COMPLETED" && !!payment.journalId && uuid.test(payment.journalId);
