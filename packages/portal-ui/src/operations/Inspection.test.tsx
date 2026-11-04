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
