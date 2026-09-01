# Linux Preflight end-to-end evidence — Big Red — 2026-09-01

## Final result

- Candidate JAR SHA-256: `2d450a1ac200c7c310c4e6edc2576d8c7c61063722c00fac1d24cffe33f4da1b`
- `doctor`: exit 0; selected `starsector.sh`; current profile's prepared data ready and valid.
- Direct launch: 4096x2560 fullscreen, sound disabled, no launcher click.
- Warm learned-pack startup: 18.27 seconds from the game-start log marker to graphics preload.
- Held-open final launch: normal Starsector 0.98a-RC8 menu rendered at 62 FPS; clean Alt-F4 exit 0.
- Final adapter: 23 exact transformations, 0 contained failures, 0 cache-rejection signals,
  0 wrapper failures, and 0 runtime-integrity failures.
- Compact learned pack: 683,611,501 bytes, activated automatically after a verified clean launch.

Primary evidence:

- Warm benchmark: `/home/leo/.starsector-preflight/benchmarks/20260901-062655`
- Final visual run: `/home/leo/.starsector-preflight/runs/20260901-final-linux-2d450a1a`
- Menu screenshot: `/home/leo/.starsector-preflight/runs/20260901-final-linux-2d450a1a/main-menu.png`

## Linux failures reproduced and fixed or contained

1. The unattended campaign selected the direct protocol but appended `--direct` only for the
   one-shot shape. It opened the launcher, skipped launcher detection, and waited for a menu that
   could not appear. Evidence: `benchmarks/20260901-053604`. The command is now governed by the
   protocol, and a run set with zero accepted launches exits nonzero.
2. The Linux Starsector binaries use different obfuscated method/class names for the spec, rules,
   quote-normalization, and resource-resolver seams. Exact Linux targets and method resolution were
   added. The final warm run served 5,573 variant, 3,077 weapon, 1,263 projectile, 2,671 hull,
   62,340 rule-token, 671 rule-command, and 8,824 merged-read hits.
3. Linux `loadJSON` memoization initially disabled itself because its one-shot-state guard named
   the macOS resolver. The exact Linux resolver shape is now supported. Final evidence: 17,333
   calls, 8,988 same-process memo hits, 7,355 prepared hits, 843 correct stateful bypasses, and
   zero failures.
4. Janino complete-map replay is not semantically portable merely because the Janino class bytes
   match. Linux warm replay made existing mission resources appear absent. Evidence:
   `benchmarks/20260901-054611` and `benchmarks/20260901-055903`. The Linux target and artifact
   selection now fail closed; macOS retains the reviewed optimization.
5. The reviewed Linux launcher defaulted to Shenandoah plus `AlwaysPreTouch`; a controlled run hit
   SIGSEGV after rules loading (`benchmarks/20260901-054855`). Recommended mode now selects G1 and
   defers heap commit only for the exact reviewed Zulu 17.0.10 Linux launcher/runtime. The policy
   is fail-closed and can be disabled with `PREFLIGHT_DISABLE_LINUX_G1_POLICY`.
6. A verified clean recommended launch now automatically graduates a full learned texture pack to
   the smaller ordered pack after releasing the launch lease. Failures preserve the existing pack.

## Verification and preservation

- Full Java build: success.
- Python detector/benchmark suites: 124 tests passed.
- Installed Linux binary integration test: passed against the actual `starfarer_obf.jar`.
- `git diff --check`: clean.
- Installed mod tree: 59,212 files and 90 `mod_info.json` descriptors.
- Backup preserved: `/home/leo/Games/starsector-0.98a-RC8/mods.preflight-backup-20260901-011120`.
- System audio remained muted.

`doctor` also reports that the developer checkout has no installed `~/.local/bin/preflight`
wrapper or desktop entry. That is an installation/packaging state, not a launch-readiness failure;
the candidate JAR itself completed the full direct-launch flow above.
