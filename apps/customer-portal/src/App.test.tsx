import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Session } from "@fincore/portal-ui/auth";
import App from "./App";

const mocks = vi.hoisted(() => ({ session: {} as Session }));
vi.mock("@fincore/portal-ui/auth", () => ({ useSession: () => mocks.session }));

function open() {
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <App />
    </QueryClientProvider>,
  );
}
beforeEach(() => {
  mocks.session = {
    status: "anonymous",
    roles: [],
    login: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn().mockResolvedValue(undefined),
    token: vi.fn().mockResolvedValue("synthetic-test-token"),
  };
});

describe("customer workspace", () => {
  it("offers hosted login and describes unimplemented banking features accurately", async () => {
    open();
    await userEvent.click(
      screen.getByRole("button", { name: /sign in securely/i }),
    );
    expect(mocks.session.login).toHaveBeenCalledOnce();
    expect(screen.getByText("Available in a future release")).toBeVisible();
  });
  it("shows expiration with an explicit sign-in action", () => {
    mocks.session.status = "expired";
    open();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Your session has expired",
    );
    expect(
      screen.getByRole("button", { name: /sign in again/i }),
    ).toBeVisible();
  });
  it("shows a recoverable identity-service failure", () => {
    mocks.session.status = "error";
    open();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "We couldn’t reach sign-in",
    );
  });
  it("loads real subject data through the authenticated API adapter", async () => {
    mocks.session.status = "authenticated";
    mocks.session.roles = ["CUSTOMER"];
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            JSON.stringify({
              subject: "verified-subject",
              roles: ["CUSTOMER"],
            }),
            { status: 200 },
          ),
        ),
    );
    open();
    expect(await screen.findByText("verified-subject")).toBeVisible();
    expect(fetch).toHaveBeenCalledWith(
      "http://localhost:8080/api/v1/customers/me",
      expect.objectContaining({
        headers: { Authorization: "Bearer synthetic-test-token" },
      }),
    );
  });
  it("renders API failure without claiming a connected backend", async () => {
    mocks.session.status = "authenticated";
    mocks.session.roles = ["CUSTOMER"];
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("", { status: 503 })),
    );
    open();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "We could not connect",
    );
    expect(
      screen.queryByText("Identity verified by the customer service"),
    ).not.toBeInTheDocument();
  });
});
