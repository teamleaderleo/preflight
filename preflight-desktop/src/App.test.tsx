import { render, screen } from "@testing-library/react";
import App from "./App";

vi.mock("@tauri-apps/plugin-dialog", () => ({ open: vi.fn() }));
vi.mock("@tauri-apps/api/event", () => ({ listen: vi.fn() }));

test("shows a useful ready-state home screen in browser preview", async () => {
  render(<App />);

  expect(await screen.findByText("Your launch pad is cozy and ready")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Launch Starsector" })).toBeEnabled();
  expect(screen.getByText("Your save is sacred.")).toBeInTheDocument();
});
