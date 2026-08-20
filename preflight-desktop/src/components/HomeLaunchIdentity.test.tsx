import { render, screen } from "@testing-library/react";
import { HomeLaunchIdentity } from "./HomeLaunchIdentity";

test("puts the player-named setup first and makes the full installation path focusable", () => {
  const { container } = render(
    <HomeLaunchIdentity installRoot="/Applications/Starsector" profileName="Main campaign" />,
  );

  const identity = container.querySelector(".home-launch-identity")!;
  expect(identity.children[0]).toHaveTextContent("Main campaign");
  expect(identity.children[1]).toHaveClass("home-launch-path");

  const path = screen.getByLabelText("Installation /Applications/Starsector");
  expect(path).toHaveAttribute("tabindex", "0");
  expect(path).toHaveAttribute("data-full-path", "/Applications/Starsector");
  expect(path).toHaveAttribute("title", "/Applications/Starsector");
});
