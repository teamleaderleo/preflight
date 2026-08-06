import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "./App";

vi.mock("@tauri-apps/plugin-dialog", () => ({ open: vi.fn() }));
vi.mock("@tauri-apps/api/event", () => ({ listen: vi.fn() }));

test("shows a useful ready-state home screen in browser preview", async () => {
  render(<App />);

  expect(await screen.findByText("Your launch pad is cozy and ready")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Launch Starsector" })).toBeEnabled();
  expect(screen.getByText("Your save is sacred.")).toBeInTheDocument();
});

test("preparation exposes balanced defaults, storage, and bounded resource choices", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Your launch pad is cozy and ready");
  await user.click(screen.getByRole("button", { name: "Prepare" }));

  expect(await screen.findByText("Prepare your profile")).toBeInTheDocument();
  expect(screen.getByRole("radio", { name: /Balanced/ })).toBeChecked();
  expect(screen.getByText("4.50 GB")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /Balanced4 workers/ })).toBeEnabled();
  expect(screen.getByRole("button", { name: "Prepare current profile" })).toBeEnabled();
});
