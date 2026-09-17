import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test, vi } from "vitest";
import { BattleSizeInput } from "./BattleSizeInput";

test("clearing the field stays temporary instead of committing zero", async () => {
  const user = userEvent.setup();
  const onCommit = vi.fn();
  render(<BattleSizeInput id="battle" label="Battle size" value={400} min={200} max={2000} onCommit={onCommit} />);

  const input = screen.getByRole("spinbutton", { name: "Battle size" });
  await user.clear(input);
  expect(input).toHaveValue(null);
  expect(onCommit).not.toHaveBeenCalled();

  await user.tab();
  expect(input).toHaveValue(400);
  expect(onCommit).not.toHaveBeenCalled();
});

test("replacement typing commits once the text becomes a valid in-range battle size", async () => {
  const user = userEvent.setup();
  const onCommit = vi.fn();
  render(<BattleSizeInput id="battle" label="Battle size" value={400} min={200} max={2000} onCommit={onCommit} />);

  const input = screen.getByRole("spinbutton", { name: "Battle size" });
  await user.clear(input);
  await user.type(input, "1200");

  expect(input).toHaveValue(1200);
  expect(onCommit).toHaveBeenLastCalledWith(1200);
  expect(onCommit).not.toHaveBeenCalledWith(0);
});

test("blur clamps an out-of-range edit to the configured limit", async () => {
  const user = userEvent.setup();
  const onCommit = vi.fn();
  render(<BattleSizeInput id="battle" label="Battle size" value={400} min={200} max={2000} onCommit={onCommit} />);

  const input = screen.getByRole("spinbutton", { name: "Battle size" });
  await user.clear(input);
  await user.type(input, "100");
  expect(onCommit).not.toHaveBeenCalled();
  await user.tab();

  expect(input).toHaveValue(200);
  expect(onCommit).toHaveBeenCalledWith(200);
});

test("Escape restores the authoritative value without committing the temporary edit", async () => {
  const user = userEvent.setup();
  const onCommit = vi.fn();
  render(<BattleSizeInput id="battle" label="Battle size" value={400} min={200} max={2000} onCommit={onCommit} />);

  const input = screen.getByRole("spinbutton", { name: "Battle size" });
  await user.clear(input);
  await user.keyboard("{Escape}");

  expect(input).toHaveValue(400);
  expect(onCommit).not.toHaveBeenCalled();
});
