# Linux shortcut naming

Source: `230ad95d1a2ea21a565f58e65fe0aeef855fb321` (PR #1277).

The CLI-installed game shortcut now uses `Starsector (Preflight)`; the packaged
GUI keeps `Preflight`. An owned legacy shortcut is renamed by normal reinstall,
without changing its path, ownership marker, or launch command.

## Automated and installation evidence

On Big Red, `python3 scripts/java-dev.py test cli InstallCommandTest` passed:
16 unit tests, zero failures/errors/skips. Packaged integration verification
also passed (57 tests, zero failures/errors, five skips). The new regression test
checks reinstalling the old label and preserving the command bytes and ownership.

Normal `install --game /home/leo/Games/starsector-0.98a-RC8` succeeded. The installed
command was byte-identical to its pre-install backup. The desktop file contained
the new name and description. Fresh `Gio.AppInfo.get_all()` returned both:

- `preflight.desktop`: `Starsector (Preflight)`.
- `Preflight.desktop`: `Preflight`.

Installed CLI SHA-256:
`fb59d11570ea59ddac3d67d12c5cdb1b9f657a39cc97f96dea7cf106fd830000`.
The installed GUI package was not replaced. No game or benchmark was launched.

## Native visual limitation — still open

The actual GNOME application grid was opened through the existing RDP session.
It still displayed two `Preflight` labels after reinstall. Running
`update-desktop-database` and touching the desktop file did not change the grid's
label. Fresh Gio lookup and the shell's application directories were checked;
the on-disk entry was correct. A stale shell cache is a hypothesis, not a proven
root cause. No logout, shell restart, reboot, or GPU handover was performed.

Native visual acceptance remains open in #1274. This is not a browser-preview
pass or a claim that CI verifies the live grid. Retained local screenshots are
under `benchmark-results/gui-shortcut-polish/`, including
`linux-grid-visible.png`, `linux-grid-after-database.png`, and
`linux-grid-after-touch.png`. Earlier non-grid captures are retained too.
The AT-SPI Show Apps action returned false; the shell D-Bus ShowApplications
method refused access. Native pointer input subsequently opened the grid.

CI results belong to PR #1277's exact source checks; they are separate from this
operator observation and do not establish final release package acceptance.
