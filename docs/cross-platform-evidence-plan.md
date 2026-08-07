# Cross-platform evidence plan

Preflight's cache formats, profile identities, discovery model, and runtime adapter catalog are
shared across macOS, Windows, and Linux. Platform code is concentrated in installation discovery,
launcher integration, package lifecycle, desktop control, and two narrowly gated macOS runtime
plans. The release evidence should match those boundaries.

## Evidence ladder

| Level | Environment | What it can establish | What it can't establish |
| --- | --- | --- | --- |
| Hosted package checks | GitHub-hosted Windows, Ubuntu, and macOS runners | Reproducible builds, portable cache contracts, JAR startup, package contents, install/remove behavior, checksums, and update metadata | Integration with the licensed game, GPU/audio behavior, or performance |
| Emulated game checks | A Windows or Linux guest on Apple silicon | Discovery, first-run preparation, launch, cache acceptance, fallback behavior, settings writes, and basic gameplay compatibility | Native x86-64 performance or representative driver behavior |
| Native beta checks | A user's Windows or Linux machine with its own game installation | Package UX, real-game correctness, graphics/audio behavior, startup time, and frame-time distributions | Another machine's hardware or mod profile |

Public repositories can use GitHub's standard hosted runners without Actions charges. The current
matrix already builds and tests Windows, Ubuntu, and macOS packages. It should remain the first gate
for every candidate. Public workflow artifacts are readable by people who can access the repository,
so private candidates stay encrypted before upload.

The Apple-silicon fallback is useful for compatibility. Microsoft publishes a Windows 11 Arm64 ISO,
and Windows on Arm can emulate x64 applications. A successful run there is labelled **emulated
compatibility**. Linux guests can cover the same product flow. Neither result becomes a performance
claim.

Native evidence comes from a small private beta. A tester downloads an authenticated candidate,
installs it normally, selects an existing legitimate Starsector installation, runs the automated
smoke scenario, and sends the bounded run report from Preflight. The receipt supplies a case ID;
the report supplies exact platform/runtime, profile, adapter health, cache, startup, and frame-time
evidence. No game binaries or assets are uploaded.

## Candidate sequence

1. Complete the hosted candidate matrix and retain package checksums and lifecycle results.
2. Run the packaged first-run, report upload, cancellation, retry, deletion, update, rollback, and
   full-removal flows on each available operating system.
3. Exercise a licensed installation in an emulated Windows or Linux guest when that guest is
   available. Record it as compatibility evidence.
4. Give the same exact candidate to at least one native Windows tester and one native Linux tester.
   Ask for a clean install, first preparation, two launches, a campaign roam, a combat simulation,
   and removal. The automated smoke path should cover this once its platform adapter is live-tested.
5. Accept a platform only after its report shows the expected profile, no unsafe adapter outcome,
   no corrupt-cache fallback loop, and a clean package lifecycle. Publish timing claims from native
   machines separately by hardware and profile.

## Security boundary

Do not attach an unrestricted self-hosted runner to pull-request workflows in this public
repository. GitHub warns that untrusted fork code can compromise a self-hosted machine. A private,
manually dispatched workflow pinned to a reviewed commit can be used for owned native hardware.

Reports remain opt-in and bounded. The receiving service treats each ZIP as untrusted data,
validates the fixed manifest and entries, stores it privately with automatic expiration, and never
executes its contents. Public issue reports should contain the case ID and the user's description,
not the diagnostic archive.

## Sources

- [GitHub Actions billing and usage](https://docs.github.com/en/actions/concepts/billing-and-usage)
- [GitHub-hosted runners](https://docs.github.com/en/enterprise-cloud@latest/actions/reference/runners/github-hosted-runners)
- [Self-hosted runner security](https://docs.github.com/en/actions/reference/runners/self-hosted-runners)
- [Windows 11 Arm64 ISO](https://learn.microsoft.com/en-us/windows/arm/iso)
- [Windows Arm application compatibility](https://support.microsoft.com/en-us/windows/windows-arm-based-pcs-faq-477f51df-2e3b-f68f-31b0-06f5e4f8ebb5)
- [Desktop Entry `Exec` grammar](https://specifications.freedesktop.org/desktop-entry/1.0/exec-variables.html)
