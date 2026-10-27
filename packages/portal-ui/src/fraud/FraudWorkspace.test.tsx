import { beforeEach, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Session } from "../auth";
import { FraudWorkspace } from "./FraudWorkspace";
const mocks = vi.hoisted(() => ({ session: {} as Session }));
vi.mock("../auth", () => ({ useSession: () => mocks.session }));
const payment = "00000000-0000-4000-8000-000000000001"; const caseId = "00000000-0000-4000-8000-000000000002";
const context = { actor: "verified-owner", amount: "20000.00", currency: "MAD", countryCode: "MA", recentFailures: 0, beneficiary: { saved: true, createdAt: "2026-10-20T10:00:00Z" } };
const assessment = { verdict: "REVIEW", score: 50, reasons: [{ code: "HIGH_VALUE", score: 50, observed: "20000.00", threshold: "10000.00" }] };
const item = { caseId, paymentId: payment, version: 1, status: "OPEN", createdAt: "2026-10-26T10:00:00Z", sequence: "1", context, assessment };
const response = (body: unknown) => new Response(JSON.stringify(body), { status: 200 });
function open(section = "Fraud cases") { return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } })}><FraudWorkspace section={section} /></QueryClientProvider>); }
beforeEach(() => { vi.restoreAllMocks(); mocks.session = { status: "authenticated", roles: ["FRAUD_ANALYST"], username: "analyst.demo", login: vi.fn(), logout: vi.fn(), token: vi.fn().mockResolvedValue("memory-only-analyst") }; });
it("does not fetch investigation data for an ordinary administrator", () => {
  mocks.session.roles = ["ADMIN"]; const fetch = vi.fn(); vi.stubGlobal("fetch", fetch); open();
  expect(screen.getByText("Fraud analyst access required")).toBeVisible(); expect(fetch).not.toHaveBeenCalled();
});
it("requires confirmation and retains the exact decision across an unknown response", async () => {
  const submissions: { key: string; body: unknown }[] = []; let recorded = false;
  vi.stubGlobal("fetch", vi.fn().mockImplementation((url: string, options: RequestInit) => {
    if (options.method === "POST") {
      submissions.push({ key: (options.headers as Record<string, string>)["Idempotency-Key"], body: JSON.parse(options.body as string) });
      if (submissions.length === 1) return Promise.reject(new TypeError("response lost"));
      recorded = true; return Promise.resolve(response({ status: "APPROVED", version: 2 }));
    }
    return Promise.resolve(response(url.endsWith(caseId) ? { ...item, version: recorded ? 2 : 1, status: recorded ? "APPROVED" : "OPEN", rulesVersion: 1, decisions: recorded ? [{ decisionId: "decision", actor: "signed-analyst", approve: true, reason: "REVIEW_CONFIRMED", createdAt: "2026-10-26T11:00:00Z" }] : [] } : { items: [item], nextCursor: null }));
  }));
  open(); await userEvent.click(await screen.findByRole("button", { name: "Investigate payment " + payment }));
  await userEvent.type(await screen.findByLabelText("Decision reason code"), "REVIEW_CONFIRMED");
  await userEvent.click(screen.getByRole("button", { name: "Review investigation decision" })); expect(submissions).toHaveLength(0);
  await userEvent.click(screen.getByRole("button", { name: "Confirm decision" }));
  await userEvent.click(await screen.findByRole("button", { name: "Retry same decision" }));
  await waitFor(() => expect(submissions).toHaveLength(2)); expect(submissions[1]).toEqual(submissions[0]);
  expect(submissions[0].body).toEqual({ expectedVersion: 1, approve: true, reason: "REVIEW_CONFIRMED" });
  expect(submissions[0].key).toMatch(/^[a-f0-9-]{36}$/);
  expect(await screen.findByText("Decision recorded. Payment authorization is rechecked separately.")).toBeVisible();
});
it("does not offer an approval action for a strict block", async () => {
  const blocked = { ...item, status: "BLOCKED", assessment: { ...assessment, verdict: "BLOCK", score: 100 } };
  vi.stubGlobal("fetch", vi.fn().mockImplementation((url: string) => Promise.resolve(response(url.endsWith(caseId) ? { ...blocked, rulesVersion: 1, decisions: [] } : { items: [blocked], nextCursor: null }))));
  open(); await userEvent.click(await screen.findByRole("button", { name: "Investigate payment " + payment }));
  expect(await screen.findByRole("option", { name: "Reject the payment" })).toBeInTheDocument();
  expect(screen.queryByRole("option", { name: "Approve this review" })).not.toBeInTheDocument();
});
it("publishes explicitly confirmed rules with the observed version and exact decimal strings", async () => {
  let version = 4; const changes: unknown[] = [];
  const rules = { reviewAmounts: { MAD: "10000.00", EUR: "10000.00", USD: "10000.00" }, blockAmounts: { MAD: "100000.00", EUR: "100000.00", USD: "100000.00" }, newBeneficiaryAmounts: { MAD: "5000.00", EUR: "5000.00", USD: "5000.00" }, frequencyReview: 30, frequencyBlock: 60, rapidCount: 12, velocityCount: 15, failedCount: 5, newBeneficiaryHours: 24, flaggedCountries: ["ZZ"] };
  vi.stubGlobal("fetch", vi.fn().mockImplementation((_url: string, options: RequestInit) => {
    if (options.method === "PUT") { changes.push(JSON.parse(options.body as string)); version = 5; }
    return Promise.resolve(response({ version, rules, actor: "signed-analyst", reason: "INITIAL_POLICY", createdAt: "2026-10-26T10:00:00Z" }));
  }));
  open("Fraud rules"); const amount = await screen.findByLabelText("Review amount (MAD)"); await userEvent.clear(amount); await userEvent.type(amount, "11000.00");
  await userEvent.type(screen.getByLabelText("Rule change reason code"), "POLICY_TUNING"); await userEvent.click(screen.getByRole("button", { name: "Review rule changes" })); expect(changes).toHaveLength(0);
  await userEvent.click(await screen.findByRole("button", { name: "Confirm rule changes" }));
  await waitFor(() => expect(changes).toHaveLength(1)); expect(changes[0]).toMatchObject({ expectedVersion: 4, reason: "POLICY_TUNING", rules: { reviewAmounts: { MAD: "11000.00", EUR: "10000.00", USD: "10000.00" } } });
});
