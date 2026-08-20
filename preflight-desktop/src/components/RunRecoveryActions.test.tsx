import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import { RunRecoveryActions } from "./RunRecoveryActions";

const copySetup = vi.hoisted(() => vi.fn());
const setupState = vi.hoisted(() => ({ state: "idle" as "idle" | "copying" | "copied" | "error" }));

vi.mock("../useCopySetup", () => ({
  useCopySetup: () => ({
    state: setupState.state,
    text: null,
    copySetup,
    retryCopySetup: vi.fn(),
  }),
}));

beforeEach(() => {
  copySetup.mockReset();
  setupState.state = "idle";
});

function renderActions(operationBlocked = false) {
  const onRelaunch = vi.fn();
  const onGetHelp = vi.fn();
  const onDismiss = vi.fn();
  render(
    <RunRecoveryActions
      optimizationPreset="recommended"
      operationBlocked={operationBlocked}
      onRelaunch={onRelaunch}
      onGetHelp={onGetHelp}
      onDismiss={onDismiss}
    />,
  );
  return { onRelaunch, onGetHelp, onDismiss };
}

test("failed-run recovery exposes the existing privacy-safe setup-details copy action", () => {
  const { onRelaunch, onGetHelp, onDismiss } = renderActions();

  const copy = screen.getByRole("button", { name: "Copy setup details" });
  expect(copy).toHaveAttribute("title", "Copy setup details");
  fireEvent.click(copy);
  expect(copySetup).toHaveBeenCalledOnce();

  fireEvent.click(screen.getByRole("button", { name: "Relaunch" }));
  fireEvent.click(screen.getByRole("button", { name: "Get help" }));
  fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));
  expect(onRelaunch).toHaveBeenCalledOnce();
  expect(onGetHelp).toHaveBeenCalledOnce();
  expect(onDismiss).toHaveBeenCalledOnce();
});

test("copy reports its in-progress state without taking recovery ownership", () => {
  setupState.state = "copying";
  renderActions();

  expect(screen.getByRole("button", { name: "Copying setup details…" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Relaunch" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "Get help" })).toBeEnabled();
});

test("copy reports completion without removing the other recovery actions", () => {
  setupState.state = "copied";
  renderActions();

  expect(screen.getByRole("button", { name: "Setup details copied" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "Relaunch" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "Get help" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "Dismiss" })).toBeEnabled();
});

test("operation ownership blocks Relaunch and setup-details copy but keeps help/dismissal available", () => {
  renderActions(true);

  expect(screen.getByRole("button", { name: "Relaunch" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Copy setup details" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Get help" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "Dismiss" })).toBeEnabled();
});