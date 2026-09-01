import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ModDriftDrawer } from "./ModDriftDrawer";
import type { ModDriftItem } from "../types";

const mockMod: ModDriftItem = {
  modId: "nexerelin",
  modName: "Nexerelin",
  declaredVersion: "0.11.1b",
  directoryName: "Nexerelin",
  severity: "SAME_VERSION_DRIFT",
  statusSummary: "2 CSV stat tables modified locally",
  currentSignature: {
    contentSha256: "c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2",
    totalFiles: 890,
    totalBytes: 82400000,
  },
  expectedSignature: {
    contentSha256: "d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3",
    totalFiles: 890,
    totalBytes: 82394000,
  },
  modifiedFiles: [
    {
      path: "data/config/factionConfig.json",
      changeType: "MODIFIED",
      category: "CONFIG",
      currentSha256: "5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b",
      expectedSha256: "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b",
      currentSizeBytes: 14200,
      expectedSizeBytes: 13800,
      detail: "Local faction settings edited",
    },
    {
      path: "data/weapons/weapons.csv",
      changeType: "MODIFIED",
      category: "CSV",
      currentSha256: "7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d",
      expectedSha256: "2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c",
      currentSizeBytes: 84100,
      expectedSizeBytes: 84000,
      detail: "Weapon stat adjustments",
    },
  ],
  recommendation: "Re-run preparation to update cached texture and stat indexes.",
};

describe("ModDriftDrawer", () => {
  it("renders null when closed", () => {
    const { container } = render(
      <ModDriftDrawer mod={mockMod} isOpen={false} onClose={() => {}} />
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("renders drawer header and telemetry when open", () => {
    render(<ModDriftDrawer mod={mockMod} isOpen={true} onClose={() => {}} />);
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByText("Nexerelin")).toBeInTheDocument();
    expect(screen.getByText("v0.11.1b")).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
    expect(screen.getByText("890")).toBeInTheDocument();
  });

  it("filters file list by search input", () => {
    render(<ModDriftDrawer mod={mockMod} isOpen={true} onClose={() => {}} />);
    expect(screen.getByText("data/weapons/weapons.csv")).toBeInTheDocument();
    expect(screen.getByText("data/config/factionConfig.json")).toBeInTheDocument();

    const searchInput = screen.getByPlaceholderText("Filter modified file paths…");
    fireEvent.change(searchInput, { target: { value: "weapons" } });

    expect(screen.getByText("data/weapons/weapons.csv")).toBeInTheDocument();
    expect(screen.queryByText("data/config/factionConfig.json")).not.toBeInTheDocument();
  });

  it("filters file list by category tabs", () => {
    render(<ModDriftDrawer mod={mockMod} isOpen={true} onClose={() => {}} />);
    const csvTab = screen.getByRole("tab", { name: "CSV" });
    fireEvent.click(csvTab);

    expect(screen.getByText("data/weapons/weapons.csv")).toBeInTheDocument();
    expect(screen.queryByText("data/config/factionConfig.json")).not.toBeInTheDocument();
  });

  it("triggers preparation callback from footer button", () => {
    const handlePrep = vi.fn();
    const handleClose = vi.fn();
    render(
      <ModDriftDrawer
        mod={mockMod}
        isOpen={true}
        onClose={handleClose}
        onTriggerPreparation={handlePrep}
      />
    );

    const prepButton = screen.getByText("Re-run Preparation");
    fireEvent.click(prepButton);
    expect(handlePrep).toHaveBeenCalledTimes(1);
    expect(handleClose).toHaveBeenCalledTimes(1);
  });

  it("closes on Escape key press", () => {
    const handleClose = vi.fn();
    render(<ModDriftDrawer mod={mockMod} isOpen={true} onClose={handleClose} />);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(handleClose).toHaveBeenCalledTimes(1);
  });
});
