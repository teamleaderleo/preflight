import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test, vi } from "vitest";
import { GameSettingsPage } from "./GameSettingsPage";

function renderPage(loading: boolean, onRefresh = vi.fn()) {
  render(
    <GameSettingsPage
      settings={null}
      draft={null}
      loading={loading}
      saving={false}
      dirty={false}
      saveBlocked={false}
      onChange={vi.fn()}
      onRefresh={onRefresh}
      onSave={vi.fn()}
    />,
  );
  return onRefresh;
}

test("an unavailable game-settings read has a visible retry", async () => {
  const user = userEvent.setup();
  const onRefresh = renderPage(false);

  expect(screen.getByText("Game settings unavailable")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Try again" }));
  expect(onRefresh).toHaveBeenCalledOnce();
});

test("an in-progress game-settings read does not offer a duplicate retry", () => {
  renderPage(true);

  expect(screen.getByText("Reading game settings…")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
});
