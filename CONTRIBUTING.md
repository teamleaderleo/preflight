# Contributing

Preflight is pre-beta and is not taking outside code yet. Unsolicited pull requests will be closed
without review — the release surface is still moving, and reviewing against a moving surface costs
more than it saves. Bug reports are welcome.

An offer to take on an item in an issue is not an assignment, and no reply to one should be read as
granting it.

By contributing you agree that your contributions are licensed under the repository's [MIT license](LICENSE).

## Development requirements

For Java-only work:

- JDK 17
- the checked-in Maven Wrapper (`./mvnw` on macOS/Linux, `mvnw.cmd` on Windows), which downloads the reviewed Maven 3.9.16 distribution on first use

For repository-wide desktop and report-intake verification, also install:

- Node.js 22
- npm
- the stable Rust toolchain with `rustfmt` and `clippy`

Run the Java verification suite:

```bash
./mvnw verify
```

On Windows Command Prompt or PowerShell, use:

```text
mvnw.cmd verify
```

Before merging a change that crosses Java, desktop, packaging, or report-intake boundaries, run the repository-wide verification entrypoint:

```bash
bash ./scripts/verify-all.sh
```

That command runs the Maven reactor first, reuses its verified runnable JAR for the desktop packaged-engine contract, verifies the React and Rust hosts, regenerates and checks the report-intake bindings, runs Worker tests and the production dependency audit, and performs a Wrangler dry-run without deploying. Native DMG/NSIS/Debian/AppImage assembly remains in the platform GitHub Actions matrix.

## Persisted binary formats

Preflight's project-owned binary formats use registered four-byte `SPxx` magic headers. Before
adding or changing one, read [docs/binary-formats.md](docs/binary-formats.md). New production
formats use an unused magic, define an explicit version and bounded validation contract, and update
the registry in the same change. The existing `SPFC` collision is retained compatibility debt and
must not be copied as a naming pattern.

## Optional analysis profiles

Two opt-in Maven profiles are available and are intentionally kept out of the default
build so an unrelated change never breaks on them:

```bash
./mvnw -Panalysis verify   # Error Prone static analysis; reports findings as warnings
./mvnw -Pcoverage verify   # JaCoCo coverage, reported under each module's target/site/jacoco
```

`-Panalysis` reports findings as advisory warnings rather than failing the build. The
`SelfAssignment` check is disabled because it misfires on this codebase's compact record
constructor normalization. Treat new findings as a triage prompt, not an automatic gate.

## Performance changes

A performance pull request should include:

- The issue it addresses
- Before and after traces
- Raw benchmark runs
- First-build and repeat-launch numbers
- Peak-memory observations
- Compatibility and fallback behavior

## Compatibility

Avoid committing Starsector, Fast Rendering, mod, or other third-party proprietary binaries. Integration tests should use synthetic fixtures or user-supplied local paths excluded by `.gitignore`.