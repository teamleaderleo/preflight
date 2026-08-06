import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "./App";

vi.mock("@tauri-apps/plugin-dialog", () => ({ open: vi.fn(), save: vi.fn() }));
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

test("launch settings mirror vanilla display and battle controls", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Your launch pad is cozy and ready");
  await user.click(screen.getByRole("button", { name: "Launch" }));

  expect(await screen.findByText("Starsector launch settings")).toBeInTheDocument();
  expect(screen.getByLabelText("Resolution")).toHaveValue("1440x932");
  expect(screen.getByLabelText("Fullscreen")).not.toBeChecked();
  expect(screen.getByLabelText("Sound")).toBeChecked();
  expect(screen.getByLabelText("Antialiasing")).toHaveValue("0");
  expect(screen.getByLabelText("UI scaling")).toHaveValue("1");
  expect(screen.getByLabelText("Deployment-point budget")).toHaveValue("400");
  await user.click(screen.getByRole("button", { name: "Save launch settings" }));
  expect(await screen.findByText(/Launch settings saved/)).toBeInTheDocument();
});

test("profiles are preview-first and show the exact switch before applying", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Your launch pad is cozy and ready");
  await user.click(screen.getByRole("button", { name: "Profiles" }));

  expect(await screen.findByText("Your saved flight plans")).toBeInTheDocument();
  expect(screen.getByText("Heavy campaign")).toBeInTheDocument();
  expect(screen.getByText("Active")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Review switch" }));

  expect(await screen.findByRole("heading", { name: "Switch to Vanilla plus?" })).toBeInTheDocument();
  expect(screen.getByText("Enable (1)")).toBeInTheDocument();
  expect(screen.getByText("Disable (2)")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Apply switch" }));

  expect(await screen.findByText(/Switched to “Vanilla plus”/)).toBeInTheDocument();
  expect(screen.queryByRole("heading", { name: "Switch to Vanilla plus?" })).not.toBeInTheDocument();
});

test("diagnostics disclose their boundary and export a bounded bundle", async () => {
  const user = userEvent.setup();
  render(<App />);

  await screen.findByText("Your launch pad is cozy and ready");
  await user.click(screen.getByRole("button", { name: "Settings" }));

  expect(await screen.findByText("Support and diagnostics")).toBeInTheDocument();
  expect(screen.getByText("Useful metadata only")).toBeInTheDocument();
  expect(screen.getByText("Your actual game data")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Save diagnostics bundle" }));

  expect(await screen.findByText("Diagnostics are ready")).toBeInTheDocument();
  expect(screen.getByText(/Saved 14 disclosed files/)).toBeInTheDocument();
});
