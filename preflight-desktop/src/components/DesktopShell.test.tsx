import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test, vi } from "vitest";
import { DesktopShell } from "./DesktopShell";

test("Skip to workspace bypasses appearance controls for actionable recovery", async () => {
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
      <section role="alert">
        <details><summary>Technical details</summary><p>failure detail</p></details>
        <button type="button">Relaunch</button>
      </section>
    </DesktopShell>,
  );

  await user.tab();
  const skip = screen.getByRole("link", { name: "Skip to workspace" });
  expect(skip).toHaveFocus();
  expect(skip).toHaveAttribute("href", "#page-workspace");

  await user.keyboard("{Enter}");
  expect(screen.getByRole("button", { name: "Relaunch" })).toHaveFocus();
  expect(screen.getByText("Technical details")).not.toHaveFocus();
  expect(screen.getByRole("button", { name: "Use Blueprint palette" })).not.toHaveFocus();
});

function shell(page: "home" | "help", title: string) {
  return (
    <DesktopShell
      page={page}
      title={title}
      status="ready"
      isReady
      updateAvailable={false}
      engineVersion="test"
      theme="system"
      palette="blueprint"
      onPageChange={vi.fn()}
      onThemeChange={vi.fn()}
      onPaletteChange={vi.fn()}
    >
      <div>Workspace content</div>
    </DesktopShell>
  );
}

function makeScrollable(workspace: HTMLElement) {
  Object.defineProperty(workspace, "clientHeight", { configurable: true, value: 300 });
  Object.defineProperty(workspace, "scrollHeight", { configurable: true, value: 900 });
}

test("scroll keys hand focus from the newly focused page title to the named workspace", () => {
  const rendered = render(shell("home", "Home"));
  rendered.rerender(shell("help", "Help"));

  const title = screen.getByRole("heading", { name: "Help" });
  const workspace = screen.getByRole("region", { name: "Help" });
  makeScrollable(workspace);

  expect(title).toHaveFocus();
  expect(workspace).toHaveAttribute("id", "page-workspace");
  expect(workspace).toHaveAttribute("aria-labelledby", "page-title");

  fireEvent.keyDown(title, { key: "PageDown" });

  expect(workspace).toHaveFocus();
});

test("ordinary keys and effectively non-scrollable pages keep focus on the page title", () => {
  const rendered = render(shell("home", "Home"));
  rendered.rerender(shell("help", "Help"));

  const title = screen.getByRole("heading", { name: "Help" });
  const workspace = screen.getByRole("region", { name: "Help" });
  makeScrollable(workspace);

  fireEvent.keyDown(title, { key: "a" });
  expect(title).toHaveFocus();

  Object.defineProperty(workspace, "scrollHeight", { configurable: true, value: 301 });
  fireEvent.keyDown(title, { key: "PageDown" });
  expect(title).toHaveFocus();
});
