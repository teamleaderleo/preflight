import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test, vi } from "vitest";
import { DesktopShell } from "./DesktopShell";

test("Skip to workspace bypasses topbar appearance controls", async () => {
  const user = userEvent.setup();
  render(
    <DesktopShell
      page="home"
      title="Needs attention"
      status="error"
      isReady
      updateAvailable={false}
      engineVersion="test"
      theme="system"
      palette="blueprint"
      onPageChange={vi.fn()}
      onThemeChange={vi.fn()}
      onPaletteChange={vi.fn()}
    >
      <button type="button">Relaunch</button>
    </DesktopShell>,
  );

  await user.tab();
  const skip = screen.getByRole("link", { name: "Skip to workspace" });
  expect(skip).toHaveFocus();
  expect(skip).toHaveAttribute("href", "#page-workspace");

  await user.keyboard("{Enter}");
  expect(document.querySelector("#page-workspace")).toHaveFocus();
  expect(screen.getByRole("button", { name: "Use Blueprint palette" })).not.toHaveFocus();
});
