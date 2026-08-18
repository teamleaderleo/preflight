from pathlib import Path

path = Path("preflight-desktop/src/App.test.tsx")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match, found {count}: {old[:80]!r}")
    text = text.replace(old, new, 1)


replace_once(
'''  await user.clear(screen.getByLabelText("Home battle size"));
  await user.type(screen.getByLabelText("Home battle size"), "1200");
  expect(screen.getByText(/vanilla settings slider ends at 400/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Apply changes" })).toBeEnabled();
  await user.click(screen.getByRole("button", { name: "Apply changes" }));

  expect(await screen.findByText(/Game settings saved/)).toBeInTheDocument();
''',
'''  await user.clear(screen.getByLabelText("Home battle size"));
  await user.type(screen.getByLabelText("Home battle size"), "1200");
  expect(screen.getByText(/vanilla settings slider ends at 400/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Apply changes" })).toBeDisabled();
  await user.click(screen.getByRole("checkbox", { name: /I closed Starsector/i }));
  expect(screen.getByRole("button", { name: "Apply changes" })).toBeEnabled();
  await user.click(screen.getByRole("button", { name: "Apply changes" }));

  expect(await screen.findByText(/Game settings saved/)).toBeInTheDocument();
''')

replace_once(
'''test("the primary action saves edited game settings before launching", async () => {
  const user = userEvent.setup();
  const baseline = await bridge.getLaunchSettings("/Applications/Starsector");
  const pending = deferred<LaunchSettings>();
  const update = vi.spyOn(bridge, "updateLaunchSettings").mockImplementation(() => pending.promise);
  const game = vi.spyOn(bridge, "startGame").mockResolvedValue({ pid: 4242 });
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Options" }));
  await user.clear(await screen.findByLabelText("Home battle size"));
  await user.type(screen.getByLabelText("Home battle size"), "300");
  await user.click(screen.getByRole("button", { name: "Launch Starsector" }));

  expect(update).toHaveBeenCalledWith("/Applications/Starsector", expect.objectContaining({ battleSize: 300 }));
  expect(game).not.toHaveBeenCalled();
  pending.resolve({
    ...baseline,
    preferences: { ...baseline.preferences, battleSize: 300 },
  });
  await waitFor(() => expect(game).toHaveBeenCalledWith("/Applications/Starsector", "recommended", [], "minimize"));
  update.mockRestore();
  game.mockRestore();
});
''',
'''test("the primary action routes dirty game settings to reviewed Apply before launching", async () => {
  const user = userEvent.setup();
  const update = vi.spyOn(bridge, "updateLaunchSettings");
  const game = vi.spyOn(bridge, "startGame").mockResolvedValue({ pid: 4242 });
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Options" }));
  await user.clear(await screen.findByLabelText("Home battle size"));
  await user.type(screen.getByLabelText("Home battle size"), "300");
  await user.click(screen.getByRole("button", { name: "Launch Starsector" }));

  expect(await screen.findByRole("heading", { name: "Game settings", level: 1 })).toBeInTheDocument();
  expect(await screen.findByRole("alert")).toHaveTextContent("Apply your changed global game settings before launching");
  expect(update).not.toHaveBeenCalled();
  expect(game).not.toHaveBeenCalled();
  expect(screen.getByRole("button", { name: "Apply changes" })).toBeDisabled();
  update.mockRestore();
  game.mockRestore();
});
''')

replace_once(
'''test("the primary action does not launch when edited game settings fail to save", async () => {
  const user = userEvent.setup();
  const update = vi.spyOn(bridge, "updateLaunchSettings").mockRejectedValue(new Error("settings write refused"));
  const game = vi.spyOn(bridge, "startGame").mockResolvedValue({ pid: 4242 });
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Options" }));
  await user.clear(await screen.findByLabelText("Home battle size"));
  await user.type(screen.getByLabelText("Home battle size"), "300");
  await user.click(screen.getByRole("button", { name: "Launch Starsector" }));

  expect(await screen.findByText("settings write refused")).toBeInTheDocument();
  expect(screen.getByRole("alert")).toHaveTextContent("settings write refused");
  expect(game).not.toHaveBeenCalled();
  update.mockRestore();
  game.mockRestore();
});
''',
'''test("reviewed Apply failure keeps launch blocked", async () => {
  const user = userEvent.setup();
  const update = vi.spyOn(bridge, "updateLaunchSettings").mockRejectedValue(new Error("settings write refused"));
  const game = vi.spyOn(bridge, "startGame").mockResolvedValue({ pid: 4242 });
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Options" }));
  await user.clear(await screen.findByLabelText("Home battle size"));
  await user.type(screen.getByLabelText("Home battle size"), "300");
  await user.click(screen.getByRole("button", { name: "Launch Starsector" }));

  expect(await screen.findByRole("heading", { name: "Game settings", level: 1 })).toBeInTheDocument();
  expect(update).not.toHaveBeenCalled();
  await user.click(screen.getByRole("checkbox", { name: /I closed Starsector/i }));
  await user.click(screen.getByRole("button", { name: "Apply changes" }));

  expect(await screen.findByText("settings write refused")).toBeInTheDocument();
  expect(screen.getByRole("alert")).toHaveTextContent("settings write refused");
  expect(game).not.toHaveBeenCalled();
  update.mockRestore();
  game.mockRestore();
});
''')

replace_once(
'''  await user.clear(screen.getByLabelText("Home battle size"));
  await user.type(screen.getByLabelText("Home battle size"), "300");
  await user.click(screen.getByRole("button", { name: "Apply changes" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("settings write refused");
''',
'''  await user.clear(screen.getByLabelText("Home battle size"));
  await user.type(screen.getByLabelText("Home battle size"), "300");
  expect(screen.getByRole("button", { name: "Apply changes" })).toBeDisabled();
  await user.click(screen.getByRole("checkbox", { name: /I closed Starsector/i }));
  await user.click(screen.getByRole("button", { name: "Apply changes" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("settings write refused");
''')

replace_once(
'''  await user.selectOptions(screen.getByLabelText("Game memory"), "8192");
  await user.click(screen.getByRole("button", { name: "Save changes" }));
  expect(await screen.findByText(/Game settings saved/)).toBeInTheDocument();
''',
'''  await user.selectOptions(screen.getByLabelText("Game memory"), "8192");
  expect(screen.getByRole("button", { name: "Apply changes" })).toBeDisabled();
  await user.click(screen.getByRole("checkbox", { name: /I closed Starsector/i }));
  await user.click(screen.getByRole("button", { name: "Apply changes" }));
  expect(await screen.findByText(/Game settings saved/)).toBeInTheDocument();
''')

path.write_text(text, encoding="utf-8")
