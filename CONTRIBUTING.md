# Contributing

By contributing you agree that your contributions are licensed under the repository's [MIT license](LICENSE).

## Development requirements

For Java-only work:

- JDK 17
- Maven 3.9 or newer

For repository-wide desktop and report-intake verification, also install:

- Node.js 22
- npm
- the stable Rust toolchain with `rustfmt` and `clippy`

Run the Java verification suite:

```bash
mvn verify
```

Before merging a change that crosses Java, desktop, packaging, or report-intake boundaries, run the repository-wide verification entrypoint:

```bash
bash ./scripts/verify-all.sh
```

That command runs the Maven reactor first, reuses its verified runnable JAR for the desktop packaged-engine contract, verifies the React and Rust hosts, regenerates and checks the report-intake bindings, runs Worker tests and the production dependency audit, and performs a Wrangler dry-run without deploying. Native DMG/NSIS/Debian/AppImage assembly remains in the platform GitHub Actions matrix.

## Optional analysis profiles

Two opt-in Maven profiles are available and are intentionally kept out of the default
build so an unrelated change never breaks on them:

```bash
mvn -Panalysis verify   # Error Prone static analysis; reports findings as warnings
mvn -Pcoverage verify   # JaCoCo coverage, reported under each module's target/site/jacoco
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
