import { expect, test } from "vitest";
import profilesPageSource from "./ProfilesPage.tsx?raw";

test("profile review footers stay in player-facing consequence language", () => {
  expect(profilesPageSource).toContain(
    "Preflight checks the current mod list again, saves a backup, then applies this switch.",
  );
  expect(profilesPageSource).toContain(
    "Preflight checks the saved profile again before making this change.",
  );
  expect(profilesPageSource).not.toContain("replaces it safely");
  expect(profilesPageSource).not.toContain("exact reviewed profile");
});
