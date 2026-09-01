import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ModDriftBadge } from "./ModDriftBadge";

describe("ModDriftBadge", () => {
  it("renders PRISTINE severity badge", () => {
    render(<ModDriftBadge severity="PRISTINE" />);
    expect(screen.getByRole("status")).toHaveTextContent("Pristine");
  });

  it("renders SAME_VERSION_DRIFT badge with modified count", () => {
    render(<ModDriftBadge severity="SAME_VERSION_DRIFT" modifiedCount={3} />);
    expect(screen.getByRole("status")).toHaveTextContent("Content Drift");
    expect(screen.getByText("3")).toBeInTheDocument();
  });

  it("renders BYTECODE_DRIFT badge", () => {
    render(<ModDriftBadge severity="BYTECODE_DRIFT" />);
    expect(screen.getByRole("status")).toHaveTextContent("Bytecode Drift");
  });

  it("renders CORRUPT_METADATA badge", () => {
    render(<ModDriftBadge severity="CORRUPT_METADATA" />);
    expect(screen.getByRole("status")).toHaveTextContent("Corrupt Metadata");
  });

  it("renders compact mode with glyph only", () => {
    render(<ModDriftBadge severity="SAME_VERSION_DRIFT" compact />);
    expect(screen.getByRole("status")).toHaveTextContent("Δ");
  });

  it("handles onClick when interactive button", () => {
    const handleClick = vi.fn();
    render(<ModDriftBadge severity="BYTECODE_DRIFT" onClick={handleClick} />);
    const button = screen.getByRole("button");
    fireEvent.click(button);
    expect(handleClick).toHaveBeenCalledTimes(1);
  });
});
