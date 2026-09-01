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
macOS suffix `contents/resources/java/starfarer_obf.jar`. Deeper inspection corrected the initial
diagnosis: Windows also carries a distinct core-archive SHA, `CampaignState` class SHA, and
obfuscated input-batch descriptor. This is an exact Windows adapter-identity gap, not a path-only
normalization bug; save loading and gameplay were successful.

The retained all-active frame population is diagnostic only. It mixes startup, menu, and campaign
under Mesa llvmpipe because the missed semantic transitions prevented phase classification. Its
12,181 frames, 52.4 ms median, 96.6 ms p95, 208.8 ms p99, and 4.79 FPS 1% low are **not** gameplay
performance claims and are not representative of hardware rendering.

Artifacts remain on Big Red under
`/home/leo/Windows-Share/Diagnostics/20260901-windows-preflight-campaign`. The campaign screenshot
SHA-256 is `afca1675a78cfdf8986e59ed2fb227320b0c723e5fff5445c2a7cbe66ff058da`.

## Exact Windows gameplay adapter repair

PR #1201 adds reviewed Windows 0.98a-RC8 alternatives for the campaign state, frame limiter,
combat engine, combat input boundary, and interactive title overlay. Every alternative keeps the
exact Windows core-archive SHA, class SHA, method descriptor, application classloader, and platform
alternative group. It does not weaken the existing macOS or Linux gates. Five installed-core tests
exercise the real Windows archive without launching the game; all passed without skips, failures,
or errors, and the full Maven verification suite passed.

The first live candidate run used JAR SHA-256
`c3be23d64289ebe21a009f8a1420402eaf31452be6ccc9afd10f7081fc401435` and exact game PID 9764,
started at `2026-09-01T16:23:24.387Z`. All four gameplay targets present in that candidate matched
and transformed. After Continue loaded `save_LindseyEulalia_1276093397646055078`, the recorder
classified 3,623 paused campaign frames instead of zero. The paused population had a 29.1 ms
median, 51.2 ms p95, 138.7 ms p99, 7.21 FPS 1% low, 193 frames over 50 ms, and 79 over 100 ms.
Those values are **compatibility evidence only**: the VM uses llvmpipe and is not a hardware-FPS
claim.

That run also exposed a separate control-state race. Windows' decorative title-screen battle
entered `CombatState` before the Mac/Linux-only interactive-title hook could claim menu ownership,
so the controller file said `combat-ready` while the real Lindsey campaign and the frame recorder
were correctly paused-campaign active. The follow-up candidate binds the exact Windows title
overlay, publishes `main-menu-interactive`, and retains title-demo combat as non-actionable menu
telemetry. The automated paused/unpaused Lindsey scenario is the acceptance check for that
follow-up; its result belongs here even if it rejects the candidate. The first unattended attempt
did reject: the sampled/JFR scenario reached its 180-second menu deadline while the llvmpipe VM was
still loading textures, and the controller stopped the exact game PID without issuing an action.
That is a discovery-instrumentation timeout, not a failed title hook or an FPS result. A second pass
uses the existing non-JFR optimized scenario so the compatibility check is not decided by intrusive
startup overhead. That thin run did reach `main-menu-interactive`, proving the exact Windows title
transform on the installed game, but its Continue request failed closed with
`IllegalStateException: title-class-mismatch`. No campaign action or FPS claim was accepted. The
control runtime had retained the exact macOS title identity even though the transformer admitted
the separately pinned Windows identity. The next candidate makes those two reviewed identities
explicit at the control boundary; unknown and Linux title identities still decline.

The final native-Windows pass used the JAR built from the Windows checkout and run directory
`20260902-015505-windows-gameplay`. It passed the semantic Continue action, exact foreground-window
activation, paused-state verification, mapped `true -> false` pause transition, the 45-second
paused and unpaused windows, three-artifact capture, and bounded exact-PID quit. The adapter applied
27 of 27 exact matches with zero source-binding rejects or contained failures. The retained
llvmpipe figures are compatibility diagnostics, not hardware-performance claims: paused after 30
seconds recorded 1,759 frames with a 25.7 ms median, 90.3 ms p99, and 11.07 FPS 1% low; the clean
unpaused window recorded 839 frames with a 45.7 ms median, 252.4 ms p99, and 3.96 FPS 1% low.
The run captured the frame report, adapter health, and bounded log tail before stopping exact PID
8628. Evidence is mirrored under
`/home/leo/Windows-Share/Diagnostics/20260902-windows-lindsey-paused-unpaused-final`.

## Windows packaged desktop and Fast Rendering acceptance

The native Windows checkout built and verified both Tauri bundles. The package verifier accepted
`Preflight_0.1.0_x64-setup.exe`, inspected 215 entries, verified 164 bundled runtime files totaling
50,192,187 bytes with no runtime changes, and passed the bundled-engine smoke check. The native
installer exercise also completed and removed its synthetic installed fixture. The package remains
unsigned/untrusted, which is expected for this local acceptance pass rather than a release-signing
claim.

The current Fast Rendering compatibility pass used the same installed game, 1024x720 windowed
settings, enabled-mod fingerprint, Fast Rendering JAR/agent, and llvmpipe VM. Fast Rendering alone
reached the logged graphics-preload boundary in 323,423 ms. That run proved the launcher and
renderer path, but its first harness result was rejected because Java launched as `@fr.vmparams`,
which the process detector did not yet recognize; the script then force-stopped it instead of
recording a graceful close. The detector now admits that exact Fast Rendering command shape.

Preflight plus Fast Rendering reached the same graphics-preload boundary in 100,963 ms. Fast
Rendering was observed, the Preflight live adapter report was written and healthy, and 20 of 20
exact matches transformed with zero declines. The first combined attempt exposed a real evidence
gap: Fast Rendering bypassed the stock resource-completion seam that normally publishes the live
adapter snapshot. Publishing the same bounded snapshot at the independently reviewed interactive
title boundary fixed the acceptance check without depending on shutdown behavior. The accepted
combined run is `20260902-022734-windows-startup-2x2`; its Preflight JAR SHA-256 is
`9b3fedf64716b3a051dfd74d9efa8fc0dcf0efb3e0498a8fea9f4df6654e3e55`.

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
