import { beforeEach, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Session } from "../auth";
import { Cards } from "./Cards";
import { FinanceReports } from "./FinanceReports";
import { readCardAttempt } from "./cardAttempt";
import type { Account } from "./api";

const mocks = vi.hoisted(() => ({ session: {} as Session }));
vi.mock("../auth", () => ({ useSession: () => mocks.session }));
const accountId = "00000000-0000-4000-8000-000000000001";
const account: Account = { accountId, customerId: accountId, currency: "MAD", status: "ACTIVE", version: 0, pendingStatus: null, guardError: null };
function open(children = <Cards subject="signed-owner" accounts={[account]} />) { return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } })}>{children}</QueryClientProvider>); }
beforeEach(() => { sessionStorage.clear(); vi.restoreAllMocks(); mocks.session = { status: "authenticated", roles: ["CUSTOMER"], username: "customer.demo", login: vi.fn(), logout: vi.fn(), token: vi.fn().mockResolvedValue("memory-only") }; });
it("confirms a card command and retries its unchanged identity after a reload and lost response", async () => {
  const submissions: { key: string; body: unknown }[] = [];
  vi.stubGlobal("fetch", vi.fn().mockImplementation((_url: string, options: RequestInit) => {
    if (options.method === "POST") { submissions.push({ key: (options.headers as Record<string, string>)["Idempotency-Key"], body: JSON.parse(options.body as string) }); if (submissions.length === 1) return Promise.reject(new TypeError("response lost")); return Promise.resolve(new Response(JSON.stringify({ cardId: accountId, status: "ACTIVE" }), { status: 200 })); }
    return Promise.resolve(new Response(JSON.stringify({ items: [], nextCursor: null }), { status: 200 }));
  }));
  const first = open(); await userEvent.click(await screen.findByRole("button", { name: "Review new card" })); expect(submissions).toHaveLength(0);
  await userEvent.click(screen.getByRole("button", { name: "Confirm card request" })); await screen.findByRole("button", { name: "Retry same card request" });
  expect(readCardAttempt("signed-owner")?.submitted).toBe(true); expect(readCardAttempt("other-owner")).toBeNull(); expect(screen.queryByRole("button", { name: "Keep current details" })).not.toBeInTheDocument();
  first.unmount(); open(); await userEvent.click(await screen.findByRole("button", { name: "Retry same card request" })); await waitFor(() => expect(submissions).toHaveLength(2)); expect(submissions[1]).toEqual(submissions[0]); expect(submissions[0].body).toEqual({ accountId, transactionLimit: "1000.00", dailyLimit: "5000.00" }); await waitFor(() => expect(readCardAttempt("signed-owner")).toBeNull());
});
it("does not fetch reconciliation for an administrator without the explicit finance role", () => { mocks.session.roles = ["ADMIN"]; const fetch = vi.fn(); vi.stubGlobal("fetch", fetch); open(<FinanceReports />); expect(screen.getByText("Finance operator access required")).toBeVisible(); expect(fetch).not.toHaveBeenCalled(); });
