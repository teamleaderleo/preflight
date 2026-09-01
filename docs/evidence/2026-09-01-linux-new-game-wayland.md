# Linux new-game desktop smoke breadcrumbs (2026-09-01)

## Scope and invariants

- Game: `/home/leo/Games/starsector-0.98a-RC8`
- Exploration run: `~/.starsector-preflight/runs/linux-new-game-exploration`
- Preserve the verified `mods/` tree and
  `mods.preflight-backup-20260901-011120`; neither is an automation scratch area.
- Host audio was muted before launch. Starsector's saved `sound` preference was also `false`.
- The concurrently running Windows VM is out of scope and must not be stopped or changed.

## Proven new-game path

The run reached `main-menu-interactive` and opened New Game through the exact game
process, then made these deliberate character choices:

- top-hat portrait;
- honorific `Sir`, name `Big Red 1`;
- sector faction group `Lud - Ocu` (keyboard choice 4);
- faction `MVS` / Machina Void Shipyards (keyboard choice 3);
- intended starting fleet: `Carrier (small)`.

Keyboard input through the compositor is reliable once the game is active:

1. type the name;
2. `Tab` to leave the text field;
3. `1` activates `1. Continue`;
4. `Enter` activates `1. Proceed`;
5. `4`, then `3`, select the faction group and MVS.

`Escape` reliably returns from the MVS fleet picker to the faction list.

## Window ownership and HiDPI findings

- Runtime PID was `592603`; its XWayland XID was `25165826`.
- The Starsector window has no `_NET_WM_PID`; `xdotool search --pid` therefore
  cannot satisfy the exact-PID contract on this GNOME/Wayland host.
- Wnck resolves the exact process to its XID (using XRes information), and
  `xdotool windowactivate --sync 25165826` makes it active.
- Saved Starsector preferences reported `4096x2560`, fullscreen, while GNOME used
  200% scaling. A logical 2048x1280 window capture corresponded to XWayland
  geometry 4096x2560.
- The successful main-menu mapping was logical `(1392, 572)` to XWayland
  `(2784, 1144)`, exactly 2x on each axis.
- The MVS `Carrier (small)` Select center is logical about `(1235, 625)`, hence
  XWayland `(2470, 1250)`. Both XTest and uinput button pulses at that mapped
  point were ignored by this custom fleet-picker screen even though keyboard
  input remained reliable.
- `ydotool mousemove --absolute` requires GNOME's mouse acceleration profile to
  be `flat` for reproducible placement. Restore the user's original `default`
  profile after the experiment.
- `gnome-screenshot --include-pointer` falls back to an X11 capture while the
  fullscreen game is direct-scanned out: game pixels are black but cursor pixels
  remain useful for placement diagnostics. The Wnck/GDK helper captures the game
  at logical resolution but does not include the cursor.

## Next reproducible action

1. Quit the unsaved exploration run cleanly.
2. Use `preflight launch-settings set`, at its required quiescent boundary, to
   snapshot and change only resolution from `4096x2560` to `2048x1280`.
3. Relaunch through Preflight and repeat the keyboard sequence above.
4. Confirm whether the 1:1 game resolution makes the fleet Select click reliable.
5. Encode exact PID-to-XID resolution, activation, logical coordinate handling,
   and the new-game scenario in the Linux desktop smoke driver.

## Imported Mac save relocation and campaign proof

The imported save set was not Linux-clean even though its directory copy was intact. Across the
ten live slots, the active `descriptor.xml` and `campaign.xml` files plus four in-slot `.bak`
files contained 1,920 copies of the Mac mod root
`/Applications/Starsector.app/Contents/Resources/Java/../../../mods/`. All 84 unique directory
suffixes mapped to existing directories in Big Red's verified `mods/` tree; no mapping was missing.

Preflight now exposes a guarded `save relocate` operation. It is read-only by default, validates
every target before writing, requires `--apply --confirm-game-closed`, captures recoverable source
files, and atomically replaces active and backup save XML. The live apply changed 24 files and
1,920 paths. Its source backup is:

`~/.starsector-preflight/save-relocation-backups/20260901-012857-251-907cc5e4`

Post-apply verification found 0 Mac roots and 1,920 Linux roots. The original whole-tree import
backup remains at `saves.preflight-backup-20260901-081754`.

The gray Continue button had a separate exact cause. Java preferences still named the removed
exploration slot:

`./saves/save_BigRed_7393951326867170052`

Game bytecode confirms `CampaignGameManager.return()` reads the `continue` preference and rejects
it when the directory does not exist. `save relocate` now detects that stale pointer, selects the
newest live descriptor by `slotCreationTimestamp`, backs up Java preferences, and persists the
replacement. Big Red now points at:

`./saves/save_LindseyEulalia_1276093397646055078`

The preference-only recovery snapshot is:

`~/.starsector-preflight/save-relocation-backups/20260901-014820-968-a71e2a76`

Linux input also needed two release fixes discovered during this proof:

- Mutter RemoteDesktop motion is used when available; when GNOME reports `Session creation
  inhibited`, a bounded `ydotool` fallback moves in small steps and verifies the real X pointer
  after every correction before clicking.
- Continue receives a focus assertion and Return activation after verified hover. This closes the
  observed GNOME race where hover arrived but the button pulse did not.
- Linux game-log evidence checks `<install>/starsector.log` before the cross-platform
  `<install>/logs/starsector.log` fallback.

Exact failed receipts retained for regression evidence include:

- `imported-mac-save-campaign-roam-2`: old Continue pointer absent/disabled, campaign wait timed
  out after 120 seconds.
- `imported-save-relocated-picker`: Mutter rejected session creation with `Session creation
  inhibited`.
- `imported-save-campaign-roam-final`: Return preceded the final GNOME focus assertion; campaign
  wait timed out after 120 seconds.

The passing end-to-end receipt is:

`~/.starsector-preflight/runs/imported-save-campaign-roam-final-2`

It observed `main-menu-interactive`, activated enabled Continue, observed `campaign-ready` after
5.575 seconds, held W for 3 seconds, captured the campaign screenshot/log tail/adapter health/frame
report, and closed the exact game PID cleanly. Frame evidence installed successfully and recorded
248 campaign frames at 16.735 ms mean / 20.600 ms p95; the maximum during load/settle was 135.945
ms. The bounded log tail contains two nonfatal content/mod errors (missing
`tooltip_floaterFontSize`; Font Picker left fonts unchanged) and one Version Checker network warning.

Final preservation audit: 59,212 live mod files, 90 `mod_info.json` descriptors, both the original
mods and saves backups present, no live Starsector/Preflight process, and no Windows VM action.
