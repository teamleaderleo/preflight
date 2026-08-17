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

  expect(screen.getByText("4 installed")).toBeInTheDocument();
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
  // A stock installation carries about two hundred hulls and a modded one carries more. Rendering
  // every one of them is both a long scroll and a long tab order, so the list is capped and the
  // filter is the way through it.
  const many = Array.from({ length: 220 }, (_, index) => hull(`hull-${index}`, `Hull ${index}`));
  render(<HullPicker hulls={many} selectedId="hull-0" onChoose={vi.fn()} />);

  const shown = within(screen.getByRole("group", { name: "Installed hulls" })).getAllByRole("button");
  expect(shown).toHaveLength(60);
  expect(screen.getByText("160 more — keep typing to narrow it.")).toBeInTheDocument();
});
