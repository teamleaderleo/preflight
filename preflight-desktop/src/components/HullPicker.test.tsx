import { useState } from "react";
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

test("filters the additional catalog by name and reports what the filter left", async () => {
  const user = userEvent.setup();
  const choose = vi.fn();
  render(<HullPicker hulls={catalog} selectedId="wolf" onChoose={choose} />);

  expect(screen.getByText("4 additional hulls")).toBeInTheDocument();
  const list = () => within(screen.getByRole("group", { name: "Installed hulls" }));
  expect(list().getByRole("button", { name: /Wolf/ })).toHaveAttribute("aria-pressed", "true");

  await user.type(screen.getByRole("searchbox", { name: "Filter installed hulls" }), "do");
  expect(screen.getByText("2 of 4 additional hulls")).toBeInTheDocument();
  expect(list().getByRole("button", { name: /Dominator/ })).toBeInTheDocument();
  expect(within(list().getByRole("button", { name: /Dominator/ })).getByText("capital")).toBeInTheDocument();
  expect(within(list().getByRole("button", { name: /Dominator/ })).queryByText("capital ship")).not.toBeInTheDocument();
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

test("keeps a selected matching hull visible beyond the normal 60-row slice", () => {
  const many = Array.from({ length: 220 }, (_, index) => hull(`hull-${index}`, `Hull ${index}`));
  render(<HullPicker hulls={many} selectedId="hull-180" onChoose={vi.fn()} />);

  const list = within(screen.getByRole("group", { name: "Installed hulls" }));
  const shown = list.getAllByRole("button");
  expect(shown).toHaveLength(60);
  expect(list.getByRole("button", { name: /Hull 180/ })).toHaveAttribute("aria-pressed", "true");
  expect(list.queryByRole("button", { name: /Hull 59/ })).not.toBeInTheDocument();
  expect(screen.getByText("160 more — keep typing to narrow it.")).toBeInTheDocument();
});

test("keeps a deep filtered selection visible after clearing the filter", async () => {
  const user = userEvent.setup();
  const many = [
    ...Array.from({ length: 80 }, (_, index) => hull(`hull-${index}`, `Hull ${index}`)),
    hull("rare-pick", "Rare Pick"),
  ];

  function StatefulPicker() {
    const [selectedId, setSelectedId] = useState("hull-0");
    return <HullPicker hulls={many} selectedId={selectedId} onChoose={setSelectedId} />;
  }

  render(<StatefulPicker />);
  const search = screen.getByRole("searchbox", { name: "Filter installed hulls" });
  await user.type(search, "rare");
  await user.click(screen.getByRole("button", { name: /Rare Pick/ }));
  await user.clear(search);

  const list = within(screen.getByRole("group", { name: "Installed hulls" }));
  expect(list.getAllByRole("button")).toHaveLength(60);
  expect(list.getByRole("button", { name: /Rare Pick/ })).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByText("21 more — keep typing to narrow it.")).toBeInTheDocument();
});
