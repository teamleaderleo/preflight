# Public beta roadmap

**Updated:** 2026-08-23

This page explains the remaining release sequence. The live coordination board is
[#652](https://github.com/teamleaderleo/preflight/issues/652), which owns current blocker status,
merge order, and changes caused by a concrete candidate failure or explicit maintainer decision. The
checklist mirror is [Release readiness](release-readiness.md).

## Current position

The source and rendered desktop UI have converged for the first beta. What remains is operational
candidate work rather than another general source-polish or hardening wave:

1. select and freeze one immutable candidate generation;
2. exercise the exact frozen package on a licensed native Windows installation;
3. exercise the same candidate on native x86-64 Linux;
4. retain the packaged-candidate startup benchmark, hosted lifecycle/update evidence, and exact-tag
   production report-canary receipt against that same generation; and
5. complete the hands-on packaged report-intake cancel, cleanup, retry, receipt, and delete sequence
   owned by [#965](https://github.com/teamleaderleo/preflight/issues/965).

Keep this sequence synchronized with #652 instead of extending it from open engineering issues. A
private rehearsal, a green source tree, or a complete candidate does not by itself authorize making
the first public beta GitHub release and downloadable packages live; final candidate creation and
public release remain explicit maintainer decisions.

## 1. Signing setup and private rehearsals complete

The `release-signing` Environment is configured and the signed Distribution plus three-platform
lifecycle sequence succeeded twice, including once after removing the legacy repository-level key
copies. #720 owns the completed administration record. Those packages prove the signing and package
machinery only; they are not final release evidence, and current source has moved since they were
generated.

The repository intentionally has no current `main`/tag ruleset. [#607](https://github.com/teamleaderleo/preflight/issues/607)
is closed `not planned` under that owner-selected policy. Before any tagged deployment is approved,
the `release-signing` Environment remains the human admission boundary: verify that the tag/commit is
the intended frozen accepted `main` identity.

**Exit:** complete. #720's private rehearsal and signing-secret cleanup are accepted.

## 2. Freeze one immutable candidate generation

Refresh `main`, #652, the candidate-evidence owners, and the open PR queue after the private rehearsal.
Record one accepted source revision and stop admitting unrelated source changes. When separately
authorized, produce the tagged final candidate or preserved Distribution package identity from that
frozen revision.

A source change after freeze that changes release bytes creates a new candidate generation and
invalidates affected package-dependent receipts. A checkout rebuild or an earlier rehearsal does not
inherit the final candidate's evidence. A demonstrated candidate failure or explicit maintainer
decision can still justify changed bytes; the evidence chain must then follow the new generation.

**Exit:** one source identity owns the candidate bytes that the remaining acceptance work will use.

## 3. Exercise the native Windows package

Install the exact frozen package on native Windows with licensed Starsector. Exercise installation
selection, setup/preparation, two launches, campaign/combat, adapter health and fallback, ordinary
recovery, and removal using the candidate that the first public beta would expose.

**Exit:** retained Windows evidence supports the beta's actual Windows package and gameplay claims.

## 4. Exercise native x86-64 Linux

Run the same acceptance on native x86-64 Linux with the exact frozen package. Hosted package checks
and synthetic fixtures remain useful, but they do not replace a real licensed game installation for
this final platform claim.

**Exit:** retained Linux evidence supports the beta's actual Linux package and gameplay claims.

## 5. Bind performance and hosted evidence to the package

Run the normal-versus-Preflight startup pair with the engine extracted from the accepted package
bytes. The harness verifies packaged identity metadata and refuses checkout fallback in candidate
mode. Retain the hosted package lifecycle, singleton/reacquisition/update receipts and the exact-tag
production report-canary receipt against the same generation.

Publish the candidate startup result beside the development record rather than replacing it. The
current controlled development comparison is 89.00 seconds ordinary versus 15.53 seconds with
Preflight on the reviewed 83-mod profile, with a 15.25-second low. Machine, profile, runtime, and
candidate identity stay attached to every candidate result.

**Exit:** the distributed engine and hosted package generation have the retained evidence required by
[#418](https://github.com/teamleaderleo/preflight/issues/418) and
[#818](https://github.com/teamleaderleo/preflight/issues/818).

## 6. Complete the packaged intake canary

Use the accepted candidate's production report-intake path to inspect the ZIP disclosure, begin an
upload, cancel after a partial transfer, prove cleanup and local ZIP retention, retry the same ZIP,
validate the accepted size/SHA and persisted receipt, then delete the uploaded case and verify the
required local/remote cleanup.

**Exit:** #965's final hands-on candidate sequence is accepted.

## After candidate acceptance

Finalize release notes and public copy from the accepted candidate evidence. Attach the reviewed
checksums, dependency/SBOM material, license and notices, privacy and limitation text, and platform
install/removal guidance to that same release generation. Publication remains an explicit maintainer
operation over the accepted bytes.

## Publication policy

The maintainer decision recorded in
[#950](https://github.com/teamleaderleo/preflight/issues/950) treats the 2026-08-07 Fractal Softworks
request as courtesy correspondence. A reply is outside the publication gate. Preflight remains an
independent, unofficial project and keeps its existing descriptive-use attribution and disclaimer.

## During and after beta

Broader compatibility evidence continues as beta work: reviewed large-mod scenarios, audio/visual
regressions, simulation and combat coverage, save/reload, frame-time reporting, and additional native
platform/display paths. Each claim should carry the evidence scope that supports it. Research,
routine dependency updates, and post-RC hardening stay outside the frozen candidate unless #652
records a concrete candidate failure or explicit maintainer decision that changes release priority.

Starsector already renders its own instantaneous FPS counter. The useful follow-on is an explicit,
local **Frame pacing** session result in Preflight — average FPS, one-percent low, and p95/p99 frame
time — rather than a second injected overlay. The product boundary and the existing probe are
recorded in [the FPS counter audit](evidence/2026-08-26-fps-counter-product-audit.md).

## Historical roadmap

The 2026-08-11 version is retained as a dated snapshot of the earlier release plan. It contains a
superseded Fractal-response gate and older sequencing, so use it only as historical context:

[Historical 2026-08-11-era snapshot](https://github.com/teamleaderleo/preflight/blob/6bee58e44264d222fded7ad51c04caa013d360be/docs/beta-roadmap.md)
