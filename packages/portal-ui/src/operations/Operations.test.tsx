import { beforeEach, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Session } from "../auth";
import { Operations } from "./Operations";
import { readIncidentAttempt } from "./incidentAttempt";

const mocks = vi.hoisted(() => ({ session: {} as Session }));
vi.mock("../auth", () => ({ useSession: () => mocks.session }));
const resource = "00000000-0000-4000-8000-000000000001";
const incident = { incidentId: resource, createdBy: "signed-support", resourceKind: "ACCOUNT", resourceId: resource, severity: "MEDIUM", status: "OPEN", version: 0, reason: "CUSTOMER_REPORTED", createdAt: "2026-11-02T10:00:00Z", updatedAt: "2026-11-02T10:00:00Z" };
function open(section = "Incidents") { return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } })}><Operations section={section} /></QueryClientProvider>); }
function response(data: unknown) { return Promise.resolve(new Response(JSON.stringify(data), { status: 200 })); }
beforeEach(() => { sessionStorage.clear(); vi.restoreAllMocks(); mocks.session = { status: "authenticated", roles: ["SUPPORT_AGENT"], username: "support.demo", login: vi.fn(), logout: vi.fn(), token: vi.fn().mockResolvedValue("memory-only") }; });
it("confirms an incident and retries the original key and body after a lost response and remount", async () => {
  const submissions: { key: string; body: unknown }[] = [];
  vi.stubGlobal("fetch", vi.fn().mockImplementation((url: string, options: RequestInit) => {
    if (url.endsWith("/operations/me")) return response({ subject: "signed-support", scope: "OWN_INCIDENTS" });
    if (options.method === "POST") { submissions.push({ key: (options.headers as Record<string, string>)["Idempotency-Key"], body: JSON.parse(options.body as string) }); return submissions.length === 1 ? Promise.reject(new TypeError("lost response")) : response(incident); }
    if (url.endsWith("/incidents/" + resource)) return response({ ...incident, status: "RESOLVED", version: 2 });
    return response({ items: [], nextCursor: null });
  }));
  const first = open(); await userEvent.type(await screen.findByLabelText("Resource UUID"), resource); await userEvent.click(screen.getByRole("button", { name: "Review incident" })); expect(submissions).toHaveLength(0);
  await userEvent.click(screen.getByRole("button", { name: "Confirm incident request" })); await screen.findByRole("button", { name: "Retry same incident request" });
  expect(readIncidentAttempt("signed-support")?.submitted).toBe(true); expect(readIncidentAttempt("foreign-support")).toBeNull(); expect(screen.queryByRole("button", { name: "Keep current details" })).not.toBeInTheDocument();
  first.unmount(); open(); await userEvent.click(await screen.findByRole("button", { name: "Retry same incident request" }));
  await waitFor(() => expect(submissions).toHaveLength(2)); expect(submissions[1]).toEqual(submissions[0]); expect(submissions[0].body).toEqual({ resourceKind: "ACCOUNT", resourceId: resource, severity: "MEDIUM", reason: "CUSTOMER_REPORTED" });
  await screen.findByText("Recorded result: open at version 0. Current incident details are refreshed below.");
  await screen.findByText("resolved", { selector: ".bank-badge" }); expect(readIncidentAttempt("signed-support")).toBeNull();
});
it("does not request central audit data for a support identity", async () => {
  const fetch = vi.fn().mockImplementation(() => response({ subject: "signed-support", scope: "OWN_INCIDENTS" })); vi.stubGlobal("fetch", fetch); open("Audit");
  await screen.findByText("Audit access requires ADMIN"); expect(fetch).toHaveBeenCalledTimes(1); expect(fetch.mock.calls[0][0]).toMatch(/\/operations\/me$/);
});
it("does not request any operations data for customer or financial roles", () => {
  mocks.session.roles = ["FINANCE_OPERATOR", "FRAUD_ANALYST", "CUSTOMER"]; const fetch = vi.fn(); vi.stubGlobal("fetch", fetch); open();
  expect(screen.getByText("Operations access is required")).toBeVisible(); expect(fetch).not.toHaveBeenCalled();
});
it("keeps the incident reason selector accessible through investigation and resolution", async () => {
  let current = incident; let created = false;
  const mutations: unknown[] = [];
  vi.stubGlobal("fetch", vi.fn().mockImplementation((url: string, options: RequestInit) => {
    if (url.endsWith("/operations/me")) return response({ subject: "signed-support", scope: "OWN_INCIDENTS" });
    if (options.method === "POST") { created = true; return response(current); }
    if (options.method === "PUT") { const body = JSON.parse(options.body as string); mutations.push(body); current = { ...current, status: body.status, reason: body.reason, version: current.version + 1 }; return response(current); }
    if (url.endsWith("/incidents/" + resource)) return response(current);
    return response({ items: created && !url.includes("/history") ? [current] : [], nextCursor: null });
  }));
  open(); await userEvent.type(await screen.findByLabelText("Resource UUID"), resource); await userEvent.click(screen.getByRole("button", { name: "Review incident" })); await userEvent.click(screen.getByRole("button", { name: "Confirm incident request" }));
  const selected = within(await screen.findByRole("region", { name: "Selected incident" }));
  await userEvent.click(await selected.findByRole("button", { name: "Review investigation" })); await userEvent.click(screen.getByRole("button", { name: "Confirm incident request" }));
  await selected.findByText("investigating", { selector: ".bank-badge" });
  await userEvent.selectOptions(selected.getByRole("combobox", { name: "Reason code" }), "ISSUE_RESOLVED");
  await userEvent.click(selected.getByRole("button", { name: "Review resolution" })); await userEvent.click(screen.getByRole("button", { name: "Confirm incident request" }));
  await selected.findByText("resolved", { selector: ".bank-badge" });
  expect(mutations).toEqual([{ expectedVersion: 0, status: "INVESTIGATING", reason: "INVESTIGATION_UPDATE" }, { expectedVersion: 1, status: "RESOLVED", reason: "ISSUE_RESOLVED" }]);
});
