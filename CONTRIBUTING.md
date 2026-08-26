# Contributing

## TL;DR

Preflight is still pre-beta and **isn't accepting outside code contributions yet**. Bug reports are welcome; unsolicited PRs will be closed while the release surface is still moving.

If you're working in the repository with the maintainer/agent workflow:

```bash
./mvnw verify
```

For changes that cross Java + desktop/package/report-intake boundaries:

```bash
bash ./scripts/verify-all.sh
```

That's the contributor doorway. The rest below is setup/detail.

## Contribution status

An offer to take an issue isn't an assignment, and a reply shouldn't be read as granting one.

When outside contributions open later, contributions will be under the repository's [MIT license](LICENSE).

## Development requirements

Java-only work needs:

- JDK 17+
- the checked-in Maven Wrapper (`./mvnw` or `mvnw.cmd`)

The wrapper downloads the reviewed Maven distribution on first use.

Repository-wide desktop/report-intake work also needs:

- Node.js 22
- npm
- stable Rust with `rustfmt` and `clippy`

## Normal verification

Java:

```bash
./mvnw verify
```

Windows Command Prompt / PowerShell:

```text
mvnw.cmd verify
```

Repository-wide boundary check:

```bash
bash ./scripts/verify-all.sh
```

That entrypoint runs the Maven reactor, reuses its verified runnable JAR for the desktop contract, verifies React/Rust, checks report-intake bindings/tests/dependencies, and performs the deployment dry-run. Native installer assembly stays in the platform GitHub Actions jobs.

See [CI policy](docs/ci-philosophy.md) for why ordinary regression tests generally belong in the normal suites instead of one-off workflows.

## Optional analysis

```bash
./mvnw -Panalysis verify   # Error Prone report
./mvnw -Pcoverage verify   # JaCoCo report
```

These are analysis/reporting tools rather than percentage/style vetoes on unrelated work. Triage findings in context.

## Performance changes

A performance change should retain enough evidence to answer:

- what was slow;
- how it was measured;
- what changed;
- before/after behavior;
- first-preparation vs repeat-launch cost where relevant;
- memory/storage impact;
- compatibility/fallback behavior.

The exact evidence format depends on the question. Don't create a new permanent workflow just because an investigation produced a focused regression test.

## Proprietary/source boundary

Don't commit Starsector, mod, Fast Rendering, or other third-party proprietary binaries/assets.

Use synthetic fixtures or user-supplied local paths excluded from Git for integration work.

For the broader project map, read [How Preflight works](docs/how-preflight-works.md). For current visual work, use [UI design](docs/ui-design.md).
