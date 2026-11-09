import { beforeEach, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Session } from "../auth";
import { CustomerDiscovery } from "./CustomerDiscovery";

const mocks = vi.hoisted(() => ({ session: {} as Session }));
vi.mock("../auth", () => ({ useSession: () => mocks.session }));
const id = "00000000-0000-4000-8000-000000000021", incident = "00000000-0000-4000-8000-000000000022";
const match = { customerId: id, displayName: "Private scoped name", status: "ACTIVE", incidentId: incident };
function results() { return { items: [match], hasMore: false, expiresAt: new Date(Date.now() + 300_000).toISOString(), observedAt: new Date().toISOString() }; }
function response(body: unknown, status = 200) { return Promise.resolve(new Response(JSON.stringify(body), { status })); }
function open() { const cache = new QueryClient(), select = vi.fn(); return { cache, select, ...render(<QueryClientProvider client={cache}><CustomerDiscovery subject="signed-support" open={select} /></QueryClientProvider>) }; }
beforeEach(() => { vi.restoreAllMocks(); sessionStorage.clear(); mocks.session = { status: "authenticated", roles: ["SUPPORT_AGENT"], username: "support.demo", login: vi.fn(), logout: vi.fn(), token: vi.fn().mockResolvedValue("memory-only") }; });
it("searches only on submit, sends no client-selected cohort and keeps names out of persistence", async () => {
  const fetch = vi.fn().mockImplementation(() => response(results())); vi.stubGlobal("fetch", fetch); const { cache, select } = open(); expect(fetch).not.toHaveBeenCalled();
  await userEvent.type(screen.getByLabelText("Authorized customer name prefix"), "Private"); await userEvent.click(screen.getByRole("button", { name: "Search authorized customers" }));
  await screen.findByText(match.displayName); expect(fetch).toHaveBeenCalledTimes(1); expect(fetch.mock.calls[0][0]).toMatch(/\/admin\/support\/customers\/search$/);
  expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ namePrefix: "Private" }); expect(sessionStorage.length).toBe(0); expect(JSON.stringify(cache.getQueryCache().getAll())).not.toContain(match.displayName);
  await userEvent.click(screen.getByRole("button", { name: "Open authorized customer incident" })); expect(select).toHaveBeenCalledWith(incident); expect(screen.queryByText(match.displayName)).not.toBeInTheDocument();
});
it("withholds a previous result on a new denied attempt and distinguishes an empty authorized search", async () => {
  let n = 0; vi.stubGlobal("fetch", vi.fn().mockImplementation(() => ++n === 1 ? response(results()) : n === 2 ? response({ code: "SUPPORT_ACCESS_UNAVAILABLE" }, 403) : response({ ...results(), items: [] })));
  open(); await userEvent.type(screen.getByLabelText("Authorized customer name prefix"), "Private");
  await userEvent.click(screen.getByRole("button", { name: "Search authorized customers" })); await screen.findByText(match.displayName);
  await userEvent.click(screen.getByRole("button", { name: "Search authorized customers" })); await screen.findByRole("alert"); expect(screen.queryByText(match.displayName)).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Search authorized customers" })); await screen.findByText("No matching customers in your current scope");
});
it("rejects a short prefix before sending and rejects an already expired response", async () => {
  const fetch = vi.fn().mockImplementation(() => response({ ...results(), expiresAt: new Date(Date.now() - 1_000).toISOString() })); vi.stubGlobal("fetch", fetch); open();
  await userEvent.type(screen.getByLabelText("Authorized customer name prefix"), "ab"); await userEvent.click(screen.getByRole("button", { name: "Search authorized customers" })); await screen.findByRole("alert"); expect(fetch).not.toHaveBeenCalled();
  await userEvent.type(screen.getByLabelText("Authorized customer name prefix"), "c"); await userEvent.click(screen.getByRole("button", { name: "Search authorized customers" })); await screen.findByRole("alert"); expect(screen.queryByText(match.displayName)).not.toBeInTheDocument();
});
