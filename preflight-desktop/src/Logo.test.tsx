import { render, screen } from "@testing-library/react";
import { test, expect } from "vitest";

import Logo from "./Logo";

test("the expanded brand exposes the visible product and game name", () => {
  render(<Logo />);

  expect(screen.getByRole("img", { name: "Preflight for Starsector" })).toBeInTheDocument();
});

test("the compact brand keeps a concise accessible name", () => {
  render(<Logo compact />);

  expect(screen.getByRole("img", { name: "Preflight" })).toBeInTheDocument();
});
