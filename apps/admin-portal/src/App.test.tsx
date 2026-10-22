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
it("shows an authorized staff session without fabricated operational data", () => {
  mocks.session.roles = ["ADMIN"];
  open();
  expect(screen.getByText("Authorized staff session")).toBeVisible();
  expect(
    screen.getByText(
      "Operational data will appear as its services are introduced.",
    ),
  ).toBeVisible();
});
