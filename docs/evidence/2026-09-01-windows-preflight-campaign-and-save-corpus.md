# Windows campaign proof and cross-platform Lindsey save corpus (2026-09-01)

## Result

Preflight launched the installed Windows game into a newly created campaign and Starsector
autosaved it. This establishes the Windows launcher, Java agent, prepared-cache path, renderer, and
in-game transition together. It does **not** pass the unattended campaign contract yet: the live
semantic report stayed at `main-menu-ready`, never published `main-menu-interactive` or
`campaign-ready`, and classified zero campaign frames even while the campaign was visibly active.

The first Windows attempts used a 1920x1080 game inside a 1024x768 VM desktop. The main-menu buttons
were present but outside the visible desktop; that was a geometry mismatch, not a renderer failure.
The passing visual path launched windowed at the desktop's 1024x720 working area. New Game, the MVS
small-carrier path, the starting-rank and background choices, campaign creation, and autosave all
completed against exact game PID 4032, started at `2026-09-01T15:23:30.450Z`.

Adapter evidence remained healthy through the observed campaign: owner `STARSECTOR`, mode
`ENABLED`, transformer installed, kill switch off, and 22 transformations applied. Closing the game
window removed the window but left the game and launcher processes alive; both exact PIDs then
required a bounded force-stop. The run therefore remains an incomplete closure rather than a
sealed smoke pass.

After importing the Lindsey corpus, a second run opened Load Game, enumerated both Lindsey slots,
loaded `save_LindseyEulalia_1276093397646055078`, and reached its paused campaign. The run directory
is `20260901-155000-preflight-lindsey`; exact PID 1052 started at
`2026-09-01T15:51:31.710Z`. Its screenshot is retained beside the first run with SHA-256
`f5677d25f3f44e1820dd7364ca5e165e221b29a77e17e0e973ba0ecc1328c849`.

That run isolated the semantic-state cause. The adapter loaded the exact plan inventory, but its
diagnostics declined the Windows `CampaignState` and neighboring vanilla targets because their
code source ended in `C:/Games/Starsector/starsector-core/starfarer_obf.jar` instead of the pinned
macOS suffix `contents/resources/java/starfarer_obf.jar`. The archive SHA and exact class identity
otherwise matched. This is a Windows source-binding compatibility defect; save loading and gameplay
were successful.

The retained all-active frame population is diagnostic only. It mixes startup, menu, and campaign
under Mesa llvmpipe because the missed semantic transitions prevented phase classification. Its
12,181 frames, 52.4 ms median, 96.6 ms p95, 208.8 ms p99, and 4.79 FPS 1% low are **not** gameplay
performance claims and are not representative of hardware rendering.

Artifacts remain on Big Red under
`/home/leo/Windows-Share/Diagnostics/20260901-windows-preflight-campaign`. The campaign screenshot
SHA-256 is `afca1675a78cfdf8986e59ed2fb227320b0c723e5fff5445c2a7cbe66ff058da`.

## Curated save corpus

The cross-platform automation corpus now uses two Lindsey slots rather than an indiscriminate save
archive:

- `save_LindseyEulalia_1276093397646055078`: the current Mac Continue target and the anchor for
  paused/unpaused campaign measurement plus generated combat fixtures;
- `save_LindseyEulalia_7487418333814238931`: the historical save-load and failed-lookup evidence
  anchor.

Linux already held both relocated saves and its Continue preference was current on `127609...`.
Windows imported the same two slots, changed 498 embedded mod paths across six active/backup XML
files, and retained the originals under
`C:\Users\leo\.starsector-preflight\save-relocation-backups\20260901-154505-140-75db20ff`.
After the temporary New Game save was moved to a recoverable compatibility-breadcrumb directory,
Preflight repaired Windows Continue to `127609...` and retained the preference snapshot under
`20260901-154602-593-4b4c4470`.

Save identity is proven after the only expected platform difference. Replacing each OS's exact
mod-root prefix with `<MODROOT>/` produces matching SHA-256 values on macOS, Linux, and Windows:

| Save | File | Normalized SHA-256 |
|---|---|---|
| `127609...` | `descriptor.xml` | `9a1fc9311ae878605aeb68f1309b0127f4501298228909bd9fa1d5784e5a9938` |
| `127609...` | `campaign.xml` | `4fe97624d2e507bbe87ec514929dbc4ff70591868f0125a7d7095d7b19845837` |
| `748741...` | `descriptor.xml` | `dbb456f5ed473f0ed83ea34ac976e078a77ac575a1ddba2667ff24c2e8b9c505` |
| `748741...` | `campaign.xml` | `f0bba2edb026dfb227cd12655e6ecd8bd2611f8d7690688021028d531247a218` |

Each active XML contains 83 relocated mod-root occurrences. The normalized equality means later
Windows/Linux campaign and 1,040-DP runs can start from the same save input while retaining
platform-native paths; their runtime workload fingerprints must still reject divergent battle
evolution.

The machine-readable record is
[`data/2026-09-01-windows-preflight-campaign-and-save-corpus.json`](data/2026-09-01-windows-preflight-campaign-and-save-corpus.json).
