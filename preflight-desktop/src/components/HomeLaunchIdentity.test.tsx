import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { HomeLaunchIdentity } from "./HomeLaunchIdentity";

test("puts the player-named setup first and opens saved profiles from it", async () => {
  const user = userEvent.setup();
  const onOpenProfiles = vi.fn();
  const { container } = render(
    <HomeLaunchIdentity installRoot="/Applications/Starsector" profileName="Main campaign" onOpenProfiles={onOpenProfiles} />,
  );

  const identity = container.querySelector(".home-launch-identity")!;
  expect(identity.children[0]).toHaveTextContent("Main campaign");
  expect(identity.children[1]).toHaveClass("home-launch-path");

  await user.click(screen.getByRole("button", { name: "Open saved profiles. Current profile: Main campaign" }));
  expect(onOpenProfiles).toHaveBeenCalledOnce();

  const path = screen.getByLabelText("Installation /Applications/Starsector");
  expect(path).toHaveAttribute("tabindex", "0");
  expect(path).toHaveAttribute("data-full-path", "/Applications/Starsector");
  expect(path).toHaveAttribute("title", "/Applications/Starsector");
});
