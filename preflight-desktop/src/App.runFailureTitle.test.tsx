import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import App from "./App";

vi.mock("@tauri-apps/plugin-dialog", () => ({ open: vi.fn(), save: vi.fn() }));
vi.mock("@tauri-apps/api/event", () => ({ listen: vi.fn() }));

beforeEach(() => {
  window.localStorage.clear();
  window.history.replaceState(null, "", "/?scenario=run-failure");
});

afterEach(() => {
  window.history.replaceState(null, "", "/");
});

test("failed-run recovery owns the Home page title and launch identity", async () => {
  render(<App />);

  expect(await screen.findByRole("heading", { level: 1, name: "Needs attention" })).toBeInTheDocument();
  expect(await screen.findByRole("alert", { name: "Run needs attention" })).toBeInTheDocument();
  expect(screen.queryByText("Main campaign")).not.toBeInTheDocument();
  expect(screen.queryByLabelText("Installation /Applications/Starsector")).not.toBeInTheDocument();
});

test("settled Home keeps launch identity without repeating readiness beside the action", async () => {
  window.history.replaceState(null, "", "/?scenario=ready");
  render(<App />);

  expect(await screen.findByRole("heading", { level: 1, name: "Ready" })).toBeInTheDocument();
  expect(await screen.findByRole("button", { name: "Launch Starsector" })).toBeInTheDocument();
  expect(screen.getByText("Main campaign")).toBeInTheDocument();
  expect(screen.getByLabelText("Installation /Applications/Starsector")).toBeInTheDocument();
  expect(screen.queryByText("Ready to launch")).not.toBeInTheDocument();
});
