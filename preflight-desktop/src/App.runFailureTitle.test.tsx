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

test("failed-run recovery owns the Home page title", async () => {
  render(<App />);

  expect(await screen.findByRole("heading", { level: 1, name: "Needs attention" })).toBeInTheDocument();
  expect(await screen.findByRole("alert", { name: "Run needs attention" })).toBeInTheDocument();
});
