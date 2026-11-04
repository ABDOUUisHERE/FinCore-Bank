import { beforeEach, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Session } from "../auth";
import { CustomerSearch } from "./CustomerSearch";
import { Inspection } from "./Inspection";

const mocks = vi.hoisted(() => ({ session: {} as Session }));
vi.mock("../auth", () => ({ useSession: () => mocks.session }));
const id = "00000000-0000-4000-8000-000000000001", at = "2026-11-04T17:00:00Z";
const match = { customerId: id, displayName: "Synthetic O'Neil%_!", status: "ACTIVE", profileVersion: "9007199254740993", createdAt: at };
const page = { items: [match], nextAfterId: null, observedAt: at };
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
function open(inspect = vi.fn(), workspace = false) {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>{workspace ? <Inspection /> : <CustomerSearch inspect={inspect} />}</QueryClientProvider>);
}
async function search(prefix = "Syn") { await userEvent.type(screen.getByLabelText("Name prefix"), prefix); await userEvent.click(screen.getByRole("button", { name: "Search customers" })); }
beforeEach(() => { vi.restoreAllMocks(); mocks.session = { status: "authenticated", roles: ["ADMIN"], username: "admin.demo", login: vi.fn(), logout: vi.fn(), token: vi.fn().mockResolvedValue("memory-only") }; });

it("never shows or fetches name search for other roles", () => {
  const fetch = vi.fn(); vi.stubGlobal("fetch", fetch);
  for (const roles of [["SUPPORT_AGENT"],["CUSTOMER"],["FINANCE_OPERATOR"],["FRAUD_ANALYST"]]) { mocks.session.roles = roles; const view = open(); expect(screen.queryByLabelText("Name prefix")).not.toBeInTheDocument(); view.unmount(); }
  expect(fetch).not.toHaveBeenCalled();
});
it("validates a bounded prefix before any explicit request", async () => {
  const fetch = vi.fn(); vi.stubGlobal("fetch", fetch); open(); expect(fetch).not.toHaveBeenCalled();
  await search("ab"); expect(await screen.findByRole("alert")).toHaveTextContent("3–80 name characters"); expect(fetch).not.toHaveBeenCalled();
});
it("posts literal names in the body and preserves exact versions and match selection", async () => {
  const fetch = vi.fn().mockImplementation(() => json(page)); vi.stubGlobal("fetch", fetch); const inspect = vi.fn(); open(inspect);
  await search("  O'Neil%_!  "); await screen.findByText("9007199254740993");
  expect(fetch.mock.calls[0][0]).toMatch(/\/admin\/customers\/search$/);
  expect(fetch.mock.calls[0][1]).toMatchObject({ method: "POST", cache: "no-store" });
  expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ namePrefix: "O'Neil%_!", afterId: null });
  await userEvent.click(screen.getByRole("button", { name: "Inspect customer" })); expect(inspect).toHaveBeenCalledWith(id);
});
it("keeps the submitted prefix and exact UUID while moving through separate pages", async () => {
  const items = Array.from({ length: 20 }, (_, n) => ({ ...match, customerId: "00000000-0000-4000-8000-" + (n + 1).toString(16).padStart(12,"0"), displayName: "Synthetic match " + n }));
  const cursor = items[19].customerId;
  const fetch = vi.fn().mockImplementation((_url: string, options: { body: string }) => JSON.parse(options.body).afterId ? json({ ...page, items: [{ ...match, displayName: "Final synthetic match" }] }) : json({ ...page, items, nextAfterId: cursor }));
  vi.stubGlobal("fetch", fetch); open(); await search(); await screen.findByText("20 customers on this page. Page 1.");
  await userEvent.click(screen.getByRole("button", { name: "Next matches" })); await screen.findByText("Final synthetic match");
  expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ namePrefix: "Syn", afterId: cursor }); expect(screen.queryByText("Synthetic match 0")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Next matches" })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "First matches" })); await screen.findByText("Synthetic match 0"); expect(JSON.parse(fetch.mock.calls[2][1].body).afterId).toBeNull();
});
it("retries failed reads and removes old matches immediately when the prefix changes or clears", async () => {
  const fetch = vi.fn().mockImplementationOnce(() => json({ code: "STORAGE_UNAVAILABLE" },503)).mockImplementation(() => json(page));
  vi.stubGlobal("fetch", fetch); open(); await search(); await screen.findByRole("alert");
  expect(screen.queryByText("No customers match this name prefix on this page.")).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Try again" })); await screen.findByText(match.displayName);
  await userEvent.type(screen.getByLabelText("Name prefix"),"X"); expect(screen.queryByRole("region", { name: "Customer matches" })).not.toBeInTheDocument(); expect(fetch).toHaveBeenCalledTimes(2);
  await userEvent.click(screen.getByRole("button", { name: "Clear search" })); expect(screen.getByLabelText("Name prefix")).toHaveValue("");
});
it("opens the existing redacted inspection from a real workspace selection", async () => {
  const fetch = vi.fn().mockImplementation((url: string) => url.endsWith("/search") ? json(page) : json({ customerId: id, status: "ACTIVE", profileVersion: "9007199254740993", createdAt: at, observedAt: at }));
  vi.stubGlobal("fetch", fetch); open(vi.fn(),true); await search(); await screen.findByText(match.displayName);
  await userEvent.click(screen.getByRole("button", { name: "Inspect customer" }));
  const region = within(await screen.findByRole("region", { name: "Inspected customer" })); await region.findByText(id);
  expect(screen.getByLabelText("Resource UUID")).toHaveValue(id); expect(screen.getByRole("combobox", { name: "Resource type" })).toHaveValue("Customer");
  await waitFor(() => expect(screen.getByLabelText("Inspection result")).toHaveFocus());
  expect(fetch.mock.calls[1][1].method).toBe("GET"); expect(fetch.mock.calls[1][0]).toContain("/admin/customers/"+id);
});
