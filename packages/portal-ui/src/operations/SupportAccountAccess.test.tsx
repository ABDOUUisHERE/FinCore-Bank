import { beforeEach, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Session } from "../auth";
import { SupportAccountAccess, type Grant } from "./SupportAccountAccess";

const mocks = vi.hoisted(() => ({ session: {} as Session }));
vi.mock("../auth", () => ({ useSession: () => mocks.session }));
const id = "00000000-0000-4000-8000-000000000011";
const grant: Grant = { incidentId: id, accountId: id, recipient: "signed-support", permission: "ACCOUNT_STATUS_READ", version: "9007199254740993", state: "ACTIVE", issuedBy: "admin", issuedAt: "2026-11-05T10:00:00Z", expiresAt: "2099-11-05T10:00:00Z", revokedAt: null, reason: "INCIDENT_INVESTIGATION" };
const status = { accountId: id, currency: "MAD", recordedStatus: "GUARD_PENDING", accountVersion: "9007199254740993", pendingStatus: "FROZEN", lastGuardError: null, recordedGuardRevision: "1", accountObservedAt: "2026-11-05T10:00:00Z", ledgerState: "OBSERVED", ledger: { status: "ACTIVE", guardRevision: "1", observedAt: "2026-11-05T10:00:01Z" } };
function response(data: unknown, code = 200) { return Promise.resolve(new Response(JSON.stringify(data), { status: code })); }
function open() { const cache = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }); const draft = vi.fn(); return { cache, draft, ...render(<QueryClientProvider client={cache}><SupportAccountAccess subject="signed-support" incident={{ incidentId: id, resourceId: id, createdBy: "signed-support", status: "OPEN" }} draft={draft} disabled={false} /></QueryClientProvider>) }; }
beforeEach(() => { vi.restoreAllMocks(); sessionStorage.clear(); mocks.session = { status: "authenticated", roles: ["SUPPORT_AGENT"], username: "support.demo", login: vi.fn(), logout: vi.fn(), token: vi.fn().mockResolvedValue("memory-only") }; });
it("consults only on a click and removes the previous observation when a later request is denied", async () => {
  let calls = 0;
  const fetch = vi.fn().mockImplementation((_url: string, options: RequestInit) => options.method === "POST" ? (++calls === 1 ? response(status) : response({ code: "SUPPORT_ACCESS_UNAVAILABLE" }, 403)) : response({ ...grant, expiresAt: new Date(Date.now() + 3_600_000).toISOString() }));
  vi.stubGlobal("fetch", fetch); const { cache } = open();
  const inspect = await screen.findByRole("button", { name: "Consult scoped account status" }); expect(calls).toBe(0); expect(screen.queryByRole("button", { name: "Review temporary access" })).not.toBeInTheDocument();
  await userEvent.click(inspect); const observation = await screen.findByRole("region", { name: "Scoped account observation" }); expect(observation).toHaveTextContent("GUARD_PENDING"); expect(observation).toHaveTextContent("ACTIVE"); expect(observation).toHaveTextContent("9007199254740993");
  expect(JSON.stringify(cache.getQueryData(["operations", "signed-support", "account-access", id]))).not.toContain("recordedStatus"); expect(sessionStorage.length).toBe(0);
  await userEvent.click(inspect); await screen.findByRole("alert"); expect(screen.queryByRole("region", { name: "Scoped account observation" })).not.toBeInTheDocument(); expect(calls).toBe(2);
  const post = fetch.mock.calls.find(c => c[1].method === "POST")!; expect(JSON.parse(post[1].body)).toEqual({}); expect(post[0]).toMatch(new RegExp(id + "/account-status$"));
});
it("replaces a complete observation with explicit Ledger unavailability", async () => {
  let calls = 0; vi.stubGlobal("fetch", vi.fn().mockImplementation((_url: string, options: RequestInit) => options.method === "POST" ? response(++calls === 1 ? status : { ...status, ledgerState: "UNAVAILABLE", ledger: null }) : response({ ...grant, expiresAt: new Date(Date.now() + 3_600_000).toISOString() })));
  open(); await userEvent.click(await screen.findByRole("button", { name: "Consult scoped account status" })); await screen.findByText("Effective Ledger status");
  await userEvent.click(screen.getByRole("button", { name: "Consult scoped account status" })); await screen.findByText("Ledger status is unavailable. Recorded status does not confirm its effective state."); expect(screen.queryByText("Effective Ledger status")).not.toBeInTheDocument();
});
it("requires explicit ADMIN review with an exact string version and clears data on permission refresh", async () => {
  mocks.session.roles = ["ADMIN"]; vi.stubGlobal("fetch", vi.fn().mockImplementation(() => response(grant))); const { draft } = open();
  await userEvent.click(await screen.findByRole("button", { name: "Review access revocation" }));
  expect(draft).toHaveBeenCalledWith(expect.objectContaining({ path: "/admin/incidents/" + id + "/account-access", method: "PUT", body: { expectedVersion: "9007199254740993", action: "REVOKE", reason: "ACCESS_NO_LONGER_REQUIRED" } }));
  expect(screen.queryByRole("button", { name: "Consult scoped account status" })).not.toBeInTheDocument();
});
it("does not permit consultation under a missing, revoked or expired grant", async () => {
  const fetch = vi.fn().mockImplementation(() => response({ ...grant, state: "REVOKED", expiresAt: new Date(Date.now() - 1_000).toISOString() })); vi.stubGlobal("fetch", fetch); open();
  await waitFor(() => expect(screen.getByRole("button", { name: "Consult scoped account status" })).toBeDisabled()); expect(fetch).toHaveBeenCalledTimes(1);
});
