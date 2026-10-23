import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Session } from "../auth";
import { Banking } from "./Banking";
import { PaymentResult, Transfers } from "./Transfers";
import { type Account, type Payment } from "./api";
import { decimal, amount, minor, money } from "./money";
import { readAttempt } from "./attempt";

const mocks = vi.hoisted(() => ({ session: {} as Session }));
vi.mock("../auth", () => ({ useSession: () => mocks.session }));
const source = "00000000-0000-4000-8000-000000000001";
const destination = "00000000-0000-4000-8000-000000000002";
const paymentId = "00000000-0000-4000-8000-000000000003";
const journalId = "00000000-0000-4000-8000-000000000004";
const account = (id: string, currency: "MAD" | "EUR" = "MAD"): Account => ({
  accountId: id, customerId: source, currency, status: "ACTIVE", version: 1, pendingStatus: null, guardError: null,
});
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const accepted: Payment = { paymentId, status: "CREATED", version: 0, amount: "0.30", currency: "MAD" };
const completed: Payment = { ...accepted, status: "COMPLETED", journalId, version: 3 };
function open(children: React.ReactNode) {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } })}>{children}</QueryClientProvider>);
}
function transfers() { return open(<Transfers subject="signed-owner" accounts={[account(source),account(destination)]} beneficiaries={[]} navigate={vi.fn()} />); }
async function reviewTransfer(value = "0.30") {
  await userEvent.click(screen.getByRole("button", { name: /continue to recipient/i }));
  await userEvent.selectOptions(screen.getByLabelText("Recipient"), destination);
  await userEvent.click(screen.getByRole("button", { name: /continue to amount/i }));
  await userEvent.type(screen.getByLabelText("Amount (MAD)"), value);
  await userEvent.click(screen.getByRole("button", { name: "Review transfer" }));
}
beforeEach(() => {
  sessionStorage.clear();
  vi.restoreAllMocks();
  mocks.session = { status: "authenticated", roles: ["CUSTOMER"], username: "synthetic",
    login: vi.fn().mockResolvedValue(undefined), logout: vi.fn().mockResolvedValue(undefined), token: vi.fn().mockResolvedValue("synthetic-memory-token") };
});

describe("exact customer money", () => {
  it("keeps decimal sums and large balances exact without currency conversion", () => {
    expect(decimal(minor("0.10") + minor("0.20"))).toBe("0.30");
    expect(money("999999999999999999.99", "MAD")).toBe("999,999,999,999,999,999.99 MAD");
    expect(amount("1.2")).toBe("1.20");
    for (const invalid of ["0","-1","1.001","1e2","NaN","01.00"]) expect(() => amount(invalid)).toThrow();
  });
  it("separates currency totals without inventing exchange-rate conversion", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation((url: string) => {
      if (url.includes("/profile")) return Promise.resolve(response({ customerId: source, displayName: "Synthetic", countryCode: "MA", version: 0 }));
      if (url.endsWith("/accounts")) return Promise.resolve(response({ items: [account(source),account(destination,"EUR")], nextCursor: null }));
      if (url.includes("/beneficiaries") || url.includes("/transactions")) return Promise.resolve(response({ items: [], nextCursor: null }));
      if (url.includes("/activity")) return Promise.resolve(response({ month: "2026-10", days: [] }));
      return Promise.resolve(response({ booked: url.includes(source) ? "0.30" : "2.00", available: url.includes(source) ? "0.30" : "2.00", currency: url.includes(source) ? "MAD" : "EUR", version: 0 }));
    }));
    open(<Banking subject="signed-owner" section="Overview" navigate={vi.fn()} />);
    await waitFor(() => expect(screen.getAllByText("Total booked balance")).toHaveLength(2));
    expect(screen.getAllByText("0.30 MAD").length).toBeGreaterThan(0);
    expect(screen.getAllByText("2.00 EUR").length).toBeGreaterThan(0);
    expect(screen.queryByText("2.30")).not.toBeInTheDocument();
  });
  it("withholds totals when one authoritative balance is unavailable", async () => {
    vi.stubGlobal("fetch",vi.fn().mockImplementation((url: string) => {
      if(url.includes("/profile"))return Promise.resolve(response({ customerId:source,displayName:"Synthetic",countryCode:"MA",version:0 }));
      if(url.endsWith("/accounts"))return Promise.resolve(response({items:[account(source),account(destination)],nextCursor:null}));
      if(url.includes("/beneficiaries") || url.includes("/transactions"))return Promise.resolve(response({items:[],nextCursor:null}));
      if(url.includes("/activity"))return Promise.resolve(response({month:"2026-10",days:[]}));
      return Promise.resolve(url.includes(destination) ? response({},503) : response({booked:"1000.00",available:"1000.00",currency:"MAD",version:0}));
    }));
    open(<Banking subject="signed-owner" section="Overview" navigate={vi.fn()} />);
    expect(await screen.findByText(/Complete account and balance data is required/)).toBeVisible();
    await waitFor(() => expect(screen.getAllByRole("alert").length).toBeGreaterThan(0));
    expect(screen.queryByText("Total booked balance")).not.toBeInTheDocument();
  });
});

describe("transfer confirmation and recovery", () => {
  it("keeps an ambiguous original locked when a later retry is denied", async () => {
    let sends=0;
    vi.stubGlobal("fetch",vi.fn().mockImplementation((url: string, options: RequestInit) => {
      if(url.endsWith("/balance"))return Promise.resolve(response({booked:"1000.00",available:"1000.00",currency:"MAD"}));
      if(options.method==="POST"){sends++;return sends===1 ? Promise.reject(new TypeError("Lost first response")) : Promise.resolve(response({},403));}
      return Promise.resolve(response({items:[],nextCursor:null}));
    }));
    transfers();await reviewTransfer();await userEvent.click(await screen.findByRole("button",{name:"Confirm and send"}));
    await userEvent.click(await screen.findByRole("button",{name:"Retry same transfer"}));
    expect(await screen.findByRole("alert")).toHaveTextContent("cannot perform this action");
    expect(screen.queryByRole("button",{name:"Start a new draft after rejection"})).not.toBeInTheDocument();
    expect(readAttempt("signed-owner")?.submitted).toBe(true);
  });
  it("allows a new draft only after a definitive first preacceptance rejection", async () => {
    vi.stubGlobal("fetch",vi.fn().mockImplementation((url: string, options: RequestInit) => {
      if(url.endsWith("/balance"))return Promise.resolve(response({booked:"1000.00",available:"1000.00",currency:"MAD"}));
      return Promise.resolve(options.method==="POST" ? response({},422) : response({items:[],nextCursor:null}));
    }));
    transfers();await reviewTransfer();await userEvent.click(await screen.findByRole("button",{name:"Confirm and send"}));
    await userEvent.click(await screen.findByRole("button",{name:"Start a new draft after rejection"}));
    expect(readAttempt("signed-owner")).toBeNull();
  });
  it("persists an ambiguous submission and replays exactly the same request after remount", async () => {
    const submissions: { key: string; body: string }[] = [];
    vi.stubGlobal("fetch", vi.fn().mockImplementation((url: string, options: RequestInit) => {
      if (url.endsWith("/balance")) return Promise.resolve(response({ booked: "1000.00", available: "1000.00", currency: "MAD", version: 0 }));
      if (options.method === "POST") {
        submissions.push({ key: (options.headers as Record<string,string>)["Idempotency-Key"], body: options.body as string });
        return submissions.length === 1 ? Promise.reject(new TypeError("Injected response loss")) : Promise.resolve(response(accepted, 202));
      }
      return Promise.resolve(url.endsWith("/" + paymentId) ? response(completed) : response({ items: [], nextCursor: null }));
    }));
    const first = transfers(); await reviewTransfer();
    await userEvent.click(await screen.findByRole("button", { name: "Confirm and send" }));
    expect(await screen.findByRole("button", { name: "Retry same transfer" })).toBeEnabled();
    expect(submissions).toHaveLength(1);
    first.unmount();
    transfers();
    expect(screen.queryByLabelText("Amount (MAD)")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Retry same transfer" }));
    expect(await screen.findByText("Transfer completed")).toBeVisible();
    expect(submissions).toHaveLength(2); expect(submissions[1]).toEqual(submissions[0]);
    expect(JSON.stringify(sessionStorage)).not.toContain("synthetic-memory-token");
    expect(readAttempt("another-owner")).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "Start another transfer" }));
    expect(readAttempt("signed-owner")).toBeNull();
  });
  it("requires the authoritative GET and a journal, even when the accepted body claims success", async () => {
    let current: Payment = { ...accepted, status: "PROCESSING" };
    vi.stubGlobal("fetch", vi.fn().mockImplementation((url: string, options: RequestInit) => {
      if (url.endsWith("/balance")) return Promise.resolve(response({ booked: "1000.00", available: "1000.00", currency: "MAD" }));
      if (options.method === "POST") return Promise.resolve(response(completed, 202));
      return Promise.resolve(url.endsWith("/" + paymentId) ? response(current) : response({ items: [], nextCursor: null }));
    }));
    transfers(); await reviewTransfer(); await userEvent.click(await screen.findByRole("button", { name: "Confirm and send" }));
    expect(await screen.findByText("Waiting for final confirmation")).toBeVisible();
    expect(screen.queryByText("Transfer completed")).not.toBeInTheDocument();
    current = { ...completed, journalId: null };
    await userEvent.click(screen.getByRole("button", { name: "Refresh transfer status" }));
    expect(await screen.findByText("unconfirmed", { exact: true })).toBeVisible();
    expect(screen.queryByText("completed", { exact: true, selector: ".bank-badge" })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("button", { name: "Start another transfer" })).not.toBeInTheDocument());
    current = completed;
    await userEvent.click(screen.getByRole("button", { name: "Refresh transfer status" }));
    expect(await screen.findByText("Transfer completed")).toBeVisible();
  });
  it("blocks an imprecise amount and storage failure before sending money", async () => {
    const submit = vi.fn();
    vi.stubGlobal("fetch", vi.fn().mockImplementation((url: string, options: RequestInit) => {
      if (options.method === "POST") submit();
      return Promise.resolve(url.endsWith("/balance") ? response({ booked: "1000.00", available: "1000.00", currency: "MAD" }) : response({ items: [], nextCursor: null }));
    }));
    transfers(); await reviewTransfer("0.001");
    expect(await screen.findByRole("alert")).toHaveTextContent("at most two decimal places");
    await userEvent.clear(screen.getByLabelText("Amount (MAD)")); await userEvent.type(screen.getByLabelText("Amount (MAD)"),"0.30");
    vi.spyOn(Storage.prototype,"setItem").mockImplementation(() => { throw new DOMException("Unavailable", "QuotaExceededError"); });
    await userEvent.click(screen.getByRole("button", { name: "Review transfer" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("save this transfer safely");
    expect(submit).not.toHaveBeenCalled();
  });
  it("does not claim cancellation when a version race starts execution", async () => {
    let current: Payment = { ...accepted, status: "PENDING_RISK_REVIEW", version: 2 };
    vi.stubGlobal("fetch", vi.fn().mockImplementation((_url: string, options: RequestInit) => {
      if (options.method === "POST") { expect(JSON.parse(options.body as string)).toEqual({ expectedVersion: 2 }); current = { ...accepted, status: "PROCESSING", version: 3 }; return Promise.resolve(response({ code: "PAYMENT_STATE_CONFLICT" },409)); }
      return Promise.resolve(response(current));
    }));
    open(<PaymentResult subject="signed-owner" id={paymentId} />);
    await userEvent.click(await screen.findByRole("button", { name: "Cancel transfer" }));
    await userEvent.click(screen.getByRole("button", { name: "Confirm change" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("execution has started");
    await waitFor(() => expect(screen.queryByRole("button", { name: "Cancel transfer" })).not.toBeInTheDocument());
    expect(screen.queryByText("Transfer cancelled")).not.toBeInTheDocument();
  });
  it("shows a rejected owner lookup without inventing payment data", async () => {
    vi.stubGlobal("fetch",vi.fn().mockImplementation(() => Promise.resolve(response({},404))));
    open(<PaymentResult subject="other-owner" id={paymentId} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("unavailable to your identity");
    expect(screen.queryByText("Transfer completed")).not.toBeInTheDocument();
  });
});
