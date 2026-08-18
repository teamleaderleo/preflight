import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { LaunchSettingsApplyBoundary } from "./LaunchSettingsApplyBoundary";

const boundary = {
  kind: "quiescent-apply-v1" as const,
  scope: "global-starsector-settings" as const,
  instruction: "Close every settings tool and keep it closed until Apply finishes.",
  confirmationLabel: "I closed every settings tool.",
  confirmationOption: "--confirm-settings-tools-closed",
  leaseScope: "Preflight coordinates Preflight processes only. External tools remain independent.",
};

describe("launch settings Apply boundary", () => {
  it("requires the explicit acknowledgment and resets it after Apply", async () => {
    const user = userEvent.setup();
    const onApply = vi.fn();
    render(
      <LaunchSettingsApplyBoundary
        boundary={boundary}
        saving={false}
        disabled={false}
        onApply={onApply}
      />,
    );

    const apply = screen.getByRole("button", { name: "Apply changes" });
    expect(apply).toBeDisabled();
    expect(screen.getByText(boundary.leaseScope)).toBeInTheDocument();

    await user.click(screen.getByRole("checkbox", { name: /I closed every settings tool/ }));
    expect(apply).toBeEnabled();
    await user.click(apply);

    expect(onApply).toHaveBeenCalledWith(true);
    expect(apply).toBeDisabled();
  });

  it("requires a fresh acknowledgment after a blocked state", async () => {
    const user = userEvent.setup();
    const onApply = vi.fn();
    const { rerender } = render(
      <LaunchSettingsApplyBoundary
        boundary={boundary}
        saving={false}
        disabled={false}
        onApply={onApply}
      />,
    );

    const confirmation = screen.getByRole("checkbox", { name: /I closed every settings tool/ });
    const apply = screen.getByRole("button", { name: "Apply changes" });
    await user.click(confirmation);
    expect(apply).toBeEnabled();

    rerender(
      <LaunchSettingsApplyBoundary
        boundary={boundary}
        saving={false}
        disabled={true}
        blockReason="Changes can be applied after Starsector closes."
        onApply={onApply}
      />,
    );
    expect(screen.getByText("Changes can be applied after Starsector closes.")).toBeInTheDocument();
    expect(confirmation).toBeDisabled();
    expect(confirmation).not.toBeChecked();
    expect(apply).toBeDisabled();
    expect(onApply).not.toHaveBeenCalled();

    rerender(
      <LaunchSettingsApplyBoundary
        boundary={boundary}
        saving={false}
        disabled={false}
        onApply={onApply}
      />,
    );
    expect(confirmation).toBeEnabled();
    expect(confirmation).not.toBeChecked();
    expect(apply).toBeDisabled();
  });
});
