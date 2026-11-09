import { beforeEach, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Session } from "../auth";
import { SupportPaymentAccess } from "./SupportPaymentAccess";

const mocks = vi.hoisted(() => ({ session: {} as Session }));
vi.mock("../auth", () => ({ useSession: () => mocks.session }));
const id = "00000000-0000-4000-8000-000000000012";
const paymentId = "00000000-0000-4000-8000-000000000013";
const grant = { incidentId: id, paymentId, recipient: "signed-support", permission: "PAYMENT_STATUS_READ", version: "9007199254740993", state: "ACTIVE", issuedBy: "admin", issuedAt: "2026-11-09T10:00:00Z", expiresAt: "2099-11-09T10:00:00Z", revokedAt: null, reason: "CONTROL_REVIEW" };
const status = { paymentId, kind: "TRANSFER", status: "REJECTED", version: "9007199254740993", createdAt: "2026-11-09T10:00:00Z", observedAt: "2026-11-09T10:00:01Z" };
function response(data: unknown, code = 200) { return Promise.resolve(new Response(JSON.stringify(data), { status: code })); }
function open(incidentStatus = "OPEN") {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }), draft = vi.fn();
  return { cache, draft, ...render(<QueryClientProvider client={cache}><SupportPaymentAccess subject="signed-support" incident={{ incidentId: id, resourceId: paymentId, createdBy: "signed-support", status: incidentStatus }} draft={draft} disabled={false} /></QueryClientProvider>) };
}
beforeEach(() => { vi.restoreAllMocks(); sessionStorage.clear(); mocks.session = { status: "authenticated", roles: ["SUPPORT_AGENT"], username: "support.demo", login: vi.fn(), logout: vi.fn(), token: vi.fn().mockResolvedValue("memory-only") }; });
it("consults only the scoped payment on a click and removes its observation after a denial", async () => {
  let calls = 0;
  const fetch = vi.fn().mockImplementation((_url: string, options: RequestInit) => options.method === "POST" ? (++calls === 1 ? response(status) : response({ code: "SUPPORT_ACCESS_UNAVAILABLE" }, 403)) : response(grant));
  vi.stubGlobal("fetch", fetch); const { cache } = open();
  const inspect = await screen.findByRole("button", { name: "Consult scoped payment status" }); expect(calls).toBe(0);
  await userEvent.click(inspect); const observed = await screen.findByRole("region", { name: "Scoped payment observation" });
  expect(observed).toHaveTextContent(paymentId); expect(observed).toHaveTextContent("REJECTED"); expect(observed).toHaveTextContent("9007199254740993");
  expect(JSON.stringify(cache.getQueryCache().getAll().map(q => q.state.data))).not.toContain("REJECTED"); expect(sessionStorage.length).toBe(0);
  expect(screen.queryByRole("button", { name: /Review/ })).not.toBeInTheDocument();
  await userEvent.click(inspect); await screen.findByRole("alert"); expect(screen.queryByRole("region", { name: "Scoped payment observation" })).not.toBeInTheDocument();
  const posts = fetch.mock.calls.filter(c => c[1].method === "POST"); expect(posts).toHaveLength(2);
  expect(posts[0][0]).toMatch(new RegExp(id + "/payment-status$")); expect(JSON.parse(posts[0][1].body)).toEqual({});
});
it("requires ADMIN review of the fixed recipient and exact grant version", async () => {
  mocks.session.roles = ["ADMIN"]; vi.stubGlobal("fetch", vi.fn().mockImplementation(() => response(grant))); const { draft } = open();
  await userEvent.click(await screen.findByRole("button", { name: "Review temporary access" }));
  expect(draft).toHaveBeenLastCalledWith(expect.objectContaining({ path: "/admin/incidents/" + id + "/payment-access", body: { expectedVersion: "9007199254740993", action: "ISSUE", minutes: 15, reason: "INCIDENT_INVESTIGATION" }, description: expect.stringContaining("Recipient: signed-support. Payment: " + paymentId) }));
  await userEvent.click(screen.getByRole("button", { name: "Review access revocation" }));
  expect(draft).toHaveBeenLastCalledWith(expect.objectContaining({ body: { expectedVersion: "9007199254740993", action: "REVOKE", reason: "ACCESS_NO_LONGER_REQUIRED" } }));
  expect(screen.queryByRole("button", { name: "Consult scoped payment status" })).not.toBeInTheDocument();
});
it.each([{ paymentId: id }, { permission: "ACCOUNT_STATUS_READ", accountId: paymentId }])("rejects a grant for another resource or permission: %j", async mismatch => {
  const fetch = vi.fn().mockImplementation(() => response({ ...grant, ...mismatch })); vi.stubGlobal("fetch", fetch); open();
  await screen.findByRole("alert"); expect(screen.queryByRole("button", { name: "Consult scoped payment status" })).not.toBeInTheDocument(); expect(fetch).toHaveBeenCalledTimes(1);
});
it("clears the local observation on permission refresh and disables resolved incidents", async () => {
  let refreshed = false;
  vi.stubGlobal("fetch", vi.fn().mockImplementation((_url: string, options: RequestInit) => options.method === "POST" ? response(status) : response(refreshed ? { ...grant, state: "REVOKED", version: "9007199254740994" } : grant)));
  const first = open(); await userEvent.click(await screen.findByRole("button", { name: "Consult scoped payment status" })); await screen.findByRole("region", { name: "Scoped payment observation" });
  refreshed = true; await userEvent.click(screen.getByRole("button", { name: "Refresh access permission" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "Consult scoped payment status" })).toBeDisabled()); expect(screen.queryByRole("region", { name: "Scoped payment observation" })).not.toBeInTheDocument();
  first.unmount(); refreshed = false; open("RESOLVED");
  const panel = within(await screen.findByRole("region", { name: "Temporary payment access" })); await waitFor(() => expect(panel.getByRole("button", { name: "Consult scoped payment status" })).toBeDisabled());
});
