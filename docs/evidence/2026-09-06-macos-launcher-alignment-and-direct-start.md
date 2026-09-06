# Mac launcher RGB alignment and GUI direct start

Tested source: `d38a3f9e`, following main `ba657edb`. This is a native correctness observation,
not a benchmark campaign or final signed-candidate acceptance.

## Report and diagnosis

The supplied Mac screenshot shows horizontal RGB stripes and shifted/repeated logo/background
artwork in the stock Starsector launcher. The Mac launcher is over the Moonlight Windows window;
the Mac menu bar identifies Starsector and its resolution is 1440×932. Screenshot SHA-256:
`b8b74f27a6b8126c3a8093b74e2ee3ee60d4fa9b6aee9714ebdfde5196c8f796`.
The screenshot remains locally retained; it includes unrelated desktop content and is not uploaded.

Failed run `20260906-042251-535-1b83d71f` had prepared textures enabled, 20 loads, 20 hits,
zero fallbacks, and no reported internal errors. Missing cache data does not explain that run.
It used unpadded uploads, and its alignment change/restore counters were both zero.

`TextureUnpackAlignmentPlan` admitted only the exact Windows and Linux loader hashes. The exact
Mac loader already supported prepared pixels but was excluded from this guard. Inspecting the
installed `fs.common_obf.jar` bytecode found the three GL upload call sites; the guard now admits
the existing exact Mac target too. It still scopes alignment changes to owned prepared RGB buffers
whose row width needs them, and restores the previous GL state on normal and exceptional exit.
No source-binding or platform hash checks were relaxed.

The GUI also omitted `--direct` from every ordinary launch. It now reads the engine's saved-launch
availability and requests the game's existing direct-start path when available. Incomplete/new
installation preferences retain the stock launcher so setup can be completed. Existing explicit
CLI launch behavior is unchanged. The installed Mac launcher bytecode was inspected for its
direct branch and `startRes`/`startFS`/`startSound` consumption.

## Verification

- Full `mvn verify` passed.
- Focused installed-Mac loader and buffer tests: 14 executed tests passed; 23 tests requiring other
  installed fixtures were skipped. The Mac test transforms the installed class, verifies all
  resulting methods with ASM, and requires all native uploads to pass scoped alignment helpers.
- Native host: 94 library, 2 binary, and 13 integration tests passed.
- Normal Mac desktop build produced an app and DMG; installed `/Applications/Preflight.app` was
  updated from this build. DMG SHA-256:
  `20a26047ffc0f51a11925311a09e816056b0be2ed2583e6803d8b8885dbd817c`.

Corrected stock-launcher run `20260906-050342-753-edee7b7a` served the same 20 requests from cache,
with zero fallbacks/errors, and one alignment change plus one restoration. This demonstrates that
the missing guard affected an actual Mac launcher upload. The process was stopped. The computer-use
provider listed the Java app but refused its identifier, so corrected artwork was not captured or
visually accepted by the agent. A user visual check was requested; absent a response, do not claim
the screenshot defect is visually closed solely from these counters.

Actual native GUI Launch produced run `20260906-050620-360-4708b0e2` with `directLaunch=true`,
1440×932, fullscreen false, sound true. It reached `main-menu-interactive`; the GUI's Stop Starsector
control stopped it. Its prepared-pixel report recorded 15,481 hits, zero fallbacks/internal errors,
and 169 alignment changes matched by 169 restorations. Main-menu artwork/gameplay was not visually
accepted by the inaccessible Java window. The recorded clock remains
`processStartedAt → mainMenuInteractiveAt`; no timing comparison is claimed.

Retained logs: `benchmark-results/macos-launcher-*` in the Mac checkout; bytecode inspections remain
ignored local evidence, not copied game assets. No game settings, mods, Linux GPU binding, or VM
state were changed by these checks. Both owned game runs ended in `stopped` state.
