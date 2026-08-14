# The first hosted install, upgrade, rollback, and removal

**Date:** 2026-08-14
**Run:** [31824209527](https://github.com/teamleaderleo/preflight/actions/runs/31824209527), `main` at `e09b737c`
**Workflow:** `.github/workflows/package-lifecycle.yml`, dispatch-only
**Status:** first result; one run per platform, no repeat

Windows and Linux had never installed a Preflight package, upgraded it, and rolled it back. macOS
had, by hand, in [the signed rehearsal](2026-08-08-signed-update-rollback-rehearsal.md). This is the
first hosted run of that path on all three.

## What each runner reported

| | Linux | Windows | macOS |
| --- | --- | --- | --- |
| earlier package | `Preflight_0.1.0_amd64.deb` | `Preflight_0.1.0_x64-setup.exe` | `Preflight_0.1.0_aarch64.dmg` |
| later package | `Preflight_0.1.1-rehearsal_amd64.deb` | `Preflight_0.1.1-rehearsal_x64-setup.exe` | `Preflight_0.1.1-rehearsal_aarch64.dmg` |
| installed, then upgraded to | 0.1.0 → 0.1.1-rehearsal | 0.1.0 → 0.1.1-rehearsal | 0.1.0 → 0.1.1-rehearsal |
| rollback byte-identical | yes | yes | yes |
| owned files removed | 130 | 173 | 131 |
| separately owned data retained | yes | yes | yes |

"Separately owned data" is the fixture set the exercise plants in a disposable home before the first
install and rereads after every step: a prepared cache artifact, a run evidence file, a game
`settings.json`, a `mod_info.json`, and a save. None changed on any platform.

## What the run cost

Two full desktop builds per runner. Linux and macOS finished in about 20 minutes each; Windows took
roughly 35. That is why the workflow is dispatch-only.

## What it does not say

One run per platform on hosted runners. It exercises the package-manager path — `dpkg`, the NSIS
installer, a `ditto` of the mounted DMG — not the signed updater, which is driven from the packaged
UI and needs the app running. Signature verification and rejected-signature recovery remain covered
only by the macOS rehearsal above.

## What the first attempt found instead

[Run 31821656470](https://github.com/teamleaderleo/preflight/actions/runs/31821656470), an hour
earlier, failed on all three runners at the same step and never installed anything:

```
Error: root Maven project version must have exactly one writable match; found 10
```

`set-release-version.mjs` searched the whole root POM for `<version>`, found ten — junit, the
compiler plugin, an enforcer range, and the project's own — and refused to touch any of them. The
version bump every release depends on could not run, and had not been able to for as long as the
root POM has had dependencies in it. Its own test passed throughout on a fixture whose entire POM
was `<version>0.1.0</version>`. Fixed in
[#406](https://github.com/teamleaderleo/preflight/pull/406) before the run above.

The report artifacts from this run were lost: the upload step's `hashFiles` guard pointed at the
runner's temp directory, which `hashFiles` cannot see, so it skipped silently on all three. The
figures above are read from the job logs. The workflow now writes the report inside the workspace.
