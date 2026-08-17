import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test, vi } from "vitest";
import type { WireframeHull } from "../types";
import { HullPicker } from "./HullPicker";

function hull(id: string, name: string, hullSize = "FRIGATE"): WireframeHull {
  return {
    id,
    name,
    hullSize,
    style: "TEST",
    featured: false,
    bounds: [{ x: 1, y: 0 }, { x: -1, y: 1 }, { x: -1, y: -1 }],
    engines: [],
    mounts: [],
  };
}

const catalog = [
  hull("wolf", "Wolf"),
  hull("lasher", "Lasher"),
  hull("dominator", "Dominator", "CAPITAL_SHIP"),
  hull("doom", "Doom", "CRUISER"),
];

test("filters the installed catalog by name and reports what the filter left", async () => {
  const user = userEvent.setup();
  const choose = vi.fn();
  render(<HullPicker hulls={catalog} selectedId="wolf" onChoose={choose} />);

  expect(screen.getByText("4 additional hulls")).toBeInTheDocument();
  const list = () => within(screen.getByRole("group", { name: "Installed hulls" }));
  expect(list().getByRole("button", { name: /Wolf/ })).toHaveAttribute("aria-pressed", "true");

  await user.type(screen.getByRole("searchbox", { name: "Filter installed hulls" }), "do");
  expect(screen.getByText("2 of 4")).toBeInTheDocument();
  expect(list().getByRole("button", { name: /Dominator/ })).toBeInTheDocument();
  expect(list().getByRole("button", { name: /Doom/ })).toBeInTheDocument();
  expect(list().queryByRole("button", { name: /Lasher/ })).not.toBeInTheDocument();

  await user.click(list().getByRole("button", { name: /Doom/ }));
  expect(choose).toHaveBeenCalledWith("doom");
});

test("says so rather than showing an empty list when nothing matches", async () => {
  const user = userEvent.setup();
  render(<HullPicker hulls={catalog} selectedId="wolf" onChoose={vi.fn()} />);

  await user.type(screen.getByRole("searchbox", { name: "Filter installed hulls" }), "zzz");
  expect(screen.getByText(/No hull matches/)).toBeInTheDocument();
  expect(screen.queryByRole("group", { name: "Installed hulls" })).not.toBeInTheDocument();
});

test("caps how much of a large catalog it renders and says how much is left", () => {
  const many = Array.from({ length: 220 }, (_, index) => hull(`hull-${index}`, `Hull ${index}`));
  render(<HullPicker hulls={many} selectedId="hull-0" onChoose={vi.fn()} />);

  const shown = within(screen.getByRole("group", { name: "Installed hulls" })).getAllByRole("button");
  expect(shown).toHaveLength(60);
  expect(screen.getByText("160 more — keep typing to narrow it.")).toBeInTheDocument();
});

test("retains the selected hull when it lies beyond the visible slice, preserving the 60-item cap and aria-pressed=true", () => {
  const many = Array.from({ length: 220 }, (_, index) => hull(`hull-${index}`, `Hull ${index}`));
  render(<HullPicker hulls={many} selectedId="hull-150" onChoose={vi.fn()} />);

  const list = within(screen.getByRole("group", { name: "Installed hulls" }));
  const buttons = list.getAllByRole("button");
  expect(buttons).toHaveLength(60);

  const selectedButton = list.getByRole("button", { name: /Hull 150/ });
  expect(selectedButton).toBeInTheDocument();
  expect(selectedButton).toHaveAttribute("aria-pressed", "true");
});

test("retains deep filtered hull selection after clearing the search filter", async () => {
  const user = userEvent.setup();
  const many = Array.from({ length: 220 }, (_, index) => hull(`hull-${index}`, `Special Ship ${index}`));
  const { rerender } = render(<HullPicker hulls={many} selectedId="hull-0" onChoose={vi.fn()} />);

  const search = screen.getByRole("searchbox", { name: "Filter installed hulls" });
  await user.type(search, "180");
  expect(screen.getByText("1 of 220")).toBeInTheDocument();

  rerender(<HullPicker hulls={many} selectedId="hull-180" onChoose={vi.fn()} />);
  await user.clear(search);

  const list = within(screen.getByRole("group", { name: "Installed hulls" }));
  const buttons = list.getAllByRole("button");
  expect(buttons).toHaveLength(60);

  const selectedButton = list.getByRole("button", { name: /Special Ship 180/ });
  expect(selectedButton).toBeInTheDocument();
  expect(selectedButton).toHaveAttribute("aria-pressed", "true");
});

