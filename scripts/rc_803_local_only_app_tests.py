from pathlib import Path

path = Path("preflight-desktop/src/App.test.tsx")
text = path.read_text()

old_first = '''test("an unconfigured build keeps local export available and refuses report sending", async () => {
  const user = userEvent.setup();
  const intake = vi.spyOn(bridge, "getReportIntakeStatus").mockResolvedValue({
    configured: false,
    origin: null,
    reason: "Run-report sending isn't configured in this build.",
  });
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Help" }));
  await user.click(await screen.findByRole("button", { name: "Make a support file" }));

  expect(await screen.findByText(/Run-report sending isn't configured/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Review and send" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Make another one" })).toBeEnabled();
  intake.mockRestore();
});
'''
new_first = '''test("an unconfigured build keeps local export available and exposes no remote send action", async () => {
  const user = userEvent.setup();
  const intake = vi.spyOn(bridge, "getReportIntakeStatus").mockResolvedValue({
    configured: false,
    origin: null,
    reason: "Run-report sending isn't configured in this build.",
  });
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Help" }));
  await user.click(await screen.findByRole("button", { name: "Make a support file" }));

  expect(await screen.findByText(/Run-report sending isn't configured/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Review and send" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Send this exact file" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Make another one" })).toBeEnabled();
  intake.mockRestore();
});
'''
assert old_first in text
text = text.replace(old_first, new_first, 1)

old_second = '''test("a failed report send keeps one recovery alert and the local ZIP", async () => {
  const user = userEvent.setup();
  window.history.replaceState(null, "", "/?scenario=report-error");
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Help" }));
  await user.click(await screen.findByRole("button", { name: "Make a support file" }));
  await user.click(await screen.findByRole("button", { name: "Review and send" }));
  await user.click(screen.getByRole("button", { name: "Send this exact file" }));

  const alert = await screen.findByRole("alert");
  expect(alert).toHaveTextContent("It wasn’t sent");
  expect(alert).toHaveTextContent("preview report service");
  expect(alert).toHaveTextContent("still on this computer");
  expect(screen.getAllByText(/preview report service/)).toHaveLength(1);
  expect(screen.getByRole("button", { name: "Try sending again" })).toBeEnabled();
});
'''
new_second = '''test("a stale report-error preview stays local-only when the build is unconfigured", async () => {
  const user = userEvent.setup();
  const intake = vi.spyOn(bridge, "getReportIntakeStatus").mockResolvedValue({
    configured: false,
    origin: null,
    reason: "Run-report sending isn't configured in this build.",
  });
  window.history.replaceState(null, "", "/?scenario=report-error");
  render(<App />);

  await screen.findByText("Ready");
  await user.click(screen.getByRole("button", { name: "Help" }));
  await user.click(await screen.findByRole("button", { name: "Make a support file" }));

  expect(await screen.findByText(/Run-report sending isn't configured/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Make another one" })).toBeEnabled();
  expect(screen.queryByRole("button", { name: "Review and send" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Send this exact file" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Try sending again" })).not.toBeInTheDocument();
  expect(screen.queryByText(/preview report service/)).not.toBeInTheDocument();
  intake.mockRestore();
});
'''
assert old_second in text
text = text.replace(old_second, new_second, 1)

path.write_text(text)
