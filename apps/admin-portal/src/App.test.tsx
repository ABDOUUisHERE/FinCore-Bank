import { beforeEach, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Session } from "@fincore/portal-ui/auth";
import App from "./App";

const mocks = vi.hoisted(() => ({ session: {} as Session }));
vi.mock("@fincore/portal-ui/auth", () => ({ useSession: () => mocks.session }));
function open() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <App />
    </QueryClientProvider>,
  );
}
beforeEach(() => {
  mocks.session = {
    status: "authenticated",
    roles: [],
    login: vi.fn(),
    logout: vi.fn(),
    token: vi.fn(),
  };
});

it("rejects a customer identity in the staff workspace", () => {
  mocks.session.roles = ["CUSTOMER"];
  open();
  expect(screen.getByRole("alert")).toHaveTextContent("Access denied");
  expect(
    screen.queryByText("Authorized staff session"),
  ).not.toBeInTheDocument();
});
it("opens scoped incident operations after the service verifies the staff identity", async () => {
  mocks.session.roles = ["ADMIN"];
  mocks.session.token = vi.fn().mockResolvedValue("memory-only");
  vi.stubGlobal("fetch", vi.fn().mockImplementation((url: string) => Promise.resolve(new Response(JSON.stringify(url.endsWith("/operations/me") ? { subject: "signed-admin", scope: "ALL_INCIDENTS" } : { items: [], nextCursor: null }), { status: 200 }))));
  open();
  expect(await screen.findByText("Authorized staff session")).toBeVisible();
  expect(await screen.findByText("You can manage all incidents.", { exact: false })).toBeVisible();
  expect(await screen.findByText("No incidents in your scope")).toBeVisible();
});
