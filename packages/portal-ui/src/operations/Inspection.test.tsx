import { beforeEach, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Session } from "../auth";
import { Inspection } from "./Inspection";

const mocks = vi.hoisted(() => ({ session: {} as Session }));
vi.mock("../auth", () => ({ useSession: () => mocks.session }));
const id = "00000000-0000-4000-8000-000000000001";
const at = "2026-11-04T10:00:00Z";
const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
function open() { return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}><Inspection /></QueryClientProvider>); }
async function lookup(kind: string) { await userEvent.selectOptions(screen.getByRole("combobox", { name: "Resource type" }), kind); await userEvent.type(screen.getByLabelText("Resource UUID"), id); await userEvent.click(screen.getByRole("button", { name: "Inspect reference" })); }
beforeEach(() => { vi.restoreAllMocks(); mocks.session = { status: "authenticated", roles: ["ADMIN"], username: "admin.demo", login: vi.fn(), logout: vi.fn(), token: vi.fn().mockResolvedValue("memory-only") }; });

it("does not load or expose inspection for unsupported roles", () => {
  const fetch = vi.fn(); vi.stubGlobal("fetch", fetch);
  for (const roles of [["SUPPORT_AGENT"], ["CUSTOMER"], ["FRAUD_ANALYST"]]) { mocks.session.roles = roles; const view = open(); expect(screen.getByRole("alert")).toHaveTextContent("Inspection access is required"); view.unmount(); }
  expect(fetch).not.toHaveBeenCalled();
});
it("validates exact references and shows only operational customer status for admin", async () => {
  const fetch = vi.fn().mockImplementation(() => json({ customerId: id, status: "ACTIVE", profileVersion: "9007199254740993", createdAt: at, observedAt: at })); vi.stubGlobal("fetch", fetch); open();
  expect(screen.queryByRole("option", { name: "Journal" })).not.toBeInTheDocument(); expect(fetch).not.toHaveBeenCalled();
  await userEvent.type(screen.getByLabelText("Resource UUID"), "invalid"); await userEvent.click(screen.getByRole("button", { name: "Inspect reference" })); expect(await screen.findByRole("alert")).toHaveTextContent("valid resource UUID"); expect(fetch).not.toHaveBeenCalled();
  await userEvent.clear(screen.getByLabelText("Resource UUID")); await lookup("Customer");
  const result = within(await screen.findByRole("region", { name: "Inspected customer" })); await result.findByText("9007199254740993");
  expect(fetch.mock.calls[0][0]).toMatch(new RegExp("/admin/customers/" + id + "$"));
  expect(fetch.mock.calls[0][1].method).toBe("GET");
  await userEvent.selectOptions(screen.getByRole("combobox", { name: "Resource type" }), "Payment"); expect(screen.queryByRole("region", { name: "Inspected customer" })).not.toBeInTheDocument();
});
it("paginates processing history with the exact string version cursor", async () => {
  const fetch = vi.fn().mockImplementation((url: string) => json({ paymentId: id, kind: "TRANSFER", status: "COMPLETED", version: "9007199254740993", terminalCode: "POSTED", journalId: id, originalPaymentId: null, createdAt: at, observedAt: at,
    history: [{ version: url.includes("?") ? "0" : "9007199254740993", from: null, to: "CREATED", reason: null, occurredAt: at }], nextCursor: url.includes("?") ? null : "9007199254740993" })); vi.stubGlobal("fetch", fetch); open(); await lookup("Payment");
  await userEvent.click(await screen.findByRole("button", { name: "Load older transitions" })); await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
  expect(fetch.mock.calls[1][0]).toContain("?beforeVersion=9007199254740993");
  await waitFor(() => expect(screen.queryByRole("button", { name: "Load older transitions" })).not.toBeInTheDocument());
  expect(screen.getByRole("table", { name: "Recorded payment transitions" })).toHaveTextContent("0");
});
it("limits finance to journals and preserves decimal amounts and currency", async () => {
  mocks.session.roles = ["FINANCE_OPERATOR"];
  const fetch = vi.fn().mockImplementation(() => json({ journalId: id, operationId: id, currency: "MAD", total: "10.01", reversalOf: null, createdAt: at, observedAt: at, source: "ledger-postings", entries: [{ accountId: id, side: "DEBIT", amount: "10.01" }, { accountId: id, side: "CREDIT", amount: "10.01" }] })); vi.stubGlobal("fetch", fetch); open();
  expect(screen.getAllByRole("option")).toHaveLength(1); await lookup("Journal");
  const result = within(await screen.findByRole("region", { name: "Inspected journal" })); expect(await result.findByRole("table")).toHaveTextContent("10.01 MAD");
  expect(fetch.mock.calls[0][0]).toContain("/admin/ledger/journals/" + id); expect(fetch.mock.calls.every(call => call[1].method === "GET")).toBe(true);
});
const account = { accountId: id, customerId: id, currency: "MAD", recordedStatus: "GUARD_PENDING", accountVersion: "9007199254740993", pendingStatus: "FROZEN", lastGuardError: null, recordedGuardRevision: "0", accountObservedAt: at, ledgerState: "OBSERVED", ledger: { status: "FROZEN", guardRevision: "9007199254740993", observedAt: at } };
it("shows recorded intent separately from the observed Ledger guard without mutations", async () => {
  const fetch = vi.fn().mockImplementation(() => json(account)); vi.stubGlobal("fetch", fetch); open(); await lookup("Account");
  const result = within(await screen.findByRole("region", { name: "Inspected account" })); await result.findByText("GUARD_PENDING");
  const effective = within(result.getByRole("region", { name: "Observed Ledger status" })); expect(effective.getByText("FROZEN")).toBeVisible(); expect(effective.getByText("9007199254740993")).toBeVisible();
  expect(fetch.mock.calls[0][0]).toContain("/admin/accounts/" + id); expect(fetch.mock.calls[0][1].method).toBe("GET");
});
it("removes the previous effective state when a refresh reports Ledger unavailable and recovers on retry", async () => {
  let calls = 0;
  const fetch = vi.fn().mockImplementation(() => json(++calls === 2 ? { ...account, ledgerState: "UNAVAILABLE", ledger: null } : account)); vi.stubGlobal("fetch", fetch); open(); await lookup("Account");
  await screen.findByRole("region", { name: "Observed Ledger status" }); await userEvent.click(screen.getByRole("button", { name: "Refresh account" }));
  await screen.findByText("Ledger status is unavailable. The recorded account status is not confirmation of its effective state.");
  expect(screen.queryByRole("region", { name: "Observed Ledger status" })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Refresh account" })); await screen.findByRole("region", { name: "Observed Ledger status" }); expect(fetch).toHaveBeenCalledTimes(3);
});
it("does not infer absence of a Ledger account from an unrecorded provisioning link", async () => {
  vi.stubGlobal("fetch", vi.fn().mockImplementation(() => json({ ...account, recordedStatus: "PROVISIONING", pendingStatus: null, ledgerState: "NOT_LINKED", ledger: null }))); open(); await lookup("Account");
  await screen.findByText("No Ledger link is recorded yet. Provisioning may still be recovering; this does not prove that no Ledger account exists.");
  expect(screen.queryByRole("region", { name: "Observed Ledger status" })).not.toBeInTheDocument();
});
it("hides cached effective state after a failed refresh until a successful retry", async () => {
  let calls = 0;
  vi.stubGlobal("fetch", vi.fn().mockImplementation(() => ++calls === 2 ? Promise.resolve(new Response(JSON.stringify({ code: "INVALID_ACCOUNT_STATUS_SOURCE" }), { status: 503 })) : json(account)));
  open(); await lookup("Account"); await screen.findByRole("region", { name: "Observed Ledger status" });
  await userEvent.click(screen.getByRole("button", { name: "Refresh account" })); await screen.findByRole("alert");
  expect(screen.queryByRole("region", { name: "Observed Ledger status" })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Try again" })); await screen.findByRole("region", { name: "Observed Ledger status" });
});
