# Public beta roadmap

**Updated:** 2026-08-20

This page explains the release sequence. The live coordination board is
[#652](https://github.com/teamleaderleo/preflight/issues/652), which owns current blocker status,
merge order, and any promotion caused by a concrete candidate failure. The checklist mirror is
[Release readiness](release-readiness.md).

## Current position

The desktop product, recovery paths, package pipeline, startup benchmark harness, updater boundary,
and report-intake path have reached release-candidate execution. The beta now has four active
candidate/platform tasks:

1. exercise real-game installations on Windows and Linux;
2. freeze and exercise the complete hosted Windows, macOS, and Linux candidate from one accepted
   source revision;
3. run the startup benchmark on the exact packaged candidate bytes; and
4. complete the packaged report-intake cancel/retry/delete canary on that candidate.

Keep this list synchronized with #652 instead of extending it from open engineering issues.

## 1. Real-game Windows and Linux exercise

Hosted package checks and synthetic fixtures already cover package and engine contracts. Native
Windows and Linux runs provide the remaining real-game evidence for those platforms. Record exactly
which installation, launcher, display path, profile, and package generation was exercised. macOS
continues to carry the deepest development-game history.

**Exit:** the Windows and Linux real-game exercise has retained evidence suitable for the beta's
platform claims.

## 2. Freeze and exercise the complete hosted candidate

Choose one reviewed source revision and produce the complete Windows, macOS, and Linux candidate.
Verify the embedded engine, package hashes, updater metadata, legal/privacy/install files,
SBOM/dependency inventory, capability receipts, and absence of proprietary game/mod/save content.
Exercise the candidate package lifecycle through the existing hosted lanes. A code change produces a
new candidate generation.

**Exit:** every candidate byte maps to one reviewed revision and the complete hosted candidate has
accepted package evidence.

## 3. Benchmark the exact packaged candidate

Run the normal-versus-Preflight startup pair with the engine extracted from or installed by the
accepted candidate. The harness verifies packaged identity metadata and refuses checkout fallback in
candidate mode.

Publish the candidate result beside the development record: the current controlled development
comparison is 89.00 seconds ordinary versus 15.53 seconds with Preflight on the reviewed 83-mod
profile, with a 15.25-second low. Keep machine, profile, runtime, and candidate identity attached to
the claim.

**Exit:** the exact distributed engine has a retained startup result.

## 4. Complete the packaged intake canary

Use the accepted candidate's production report-intake path to review disclosure, cancel a partial
upload, verify cleanup and local ZIP retention, retry, validate the receipt, and delete the case
through the scoped path. Retain bounded evidence tied to the candidate package identity.

**Exit:** the final candidate cancel/retry/delete sequence is accepted.

## After candidate acceptance

Finalize release notes and public copy from the accepted candidate evidence. Attach the reviewed
checksums, dependency/SBOM material, license and notices, privacy and limitation text, and platform
install/removal guidance to the same release generation. Publication remains an explicit owner
operation over the accepted bytes.

Repository administration and signing configuration have their own live issue owners:
[#607](https://github.com/teamleaderleo/preflight/issues/607) for branch/tag protection verification
and [#720](https://github.com/teamleaderleo/preflight/issues/720) for the `release-signing`
Environment and release-tag admission. Use those issue bodies for current operator state instead of
copying their settings into this roadmap.

## Publication policy

The maintainer decision recorded in
[#950](https://github.com/teamleaderleo/preflight/issues/950) treats the 2026-08-07 Fractal Softworks
request as courtesy correspondence. A reply is outside the publication gate. Preflight remains an
independent, unofficial project and keeps its existing descriptive-use attribution and disclaimer.

## During and after beta

Broader compatibility evidence continues as beta work: reviewed large-mod scenarios, audio/visual
regressions, simulation and combat coverage, save/reload, frame-time reporting, and additional native
platform/display paths. Each claim should carry the evidence scope that supports it. Research and
prototype lanes stay post-RC unless #652 records a concrete candidate failure that changes release
priority.

## Historical roadmap

The 2026-08-11 version is retained as a dated snapshot of the earlier release plan. It contains a
superseded Fractal-response gate and older sequencing, so use it only as historical context:

[Historical 2026-08-11-era snapshot](https://github.com/teamleaderleo/preflight/blob/6bee58e44264d222fded7ad51c04caa013d360be/docs/beta-roadmap.md)
