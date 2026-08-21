# Public beta roadmap

**Updated:** 2026-08-21

This page explains the remaining release sequence. The live coordination board is
[#652](https://github.com/teamleaderleo/preflight/issues/652), which owns current blocker status,
merge order, and any promotion caused by a concrete candidate failure. The checklist mirror is
[Release readiness](release-readiness.md).

## Current position

The source and rendered desktop UI have converged for the first beta. What remains is operational
candidate work rather than another general source-polish or hardening wave:

1. finish the `release-signing` Environment setup and private signed rehearsal owned by
   [#720](https://github.com/teamleaderleo/preflight/issues/720);
2. freeze one immutable candidate generation after that rehearsal succeeds;
3. exercise the exact frozen package on a licensed native Windows installation;
4. exercise the same candidate on native x86-64 Linux;
5. retain the packaged-candidate startup benchmark, hosted lifecycle/update evidence, and exact-tag
   production report-canary receipt against that same generation; and
6. complete the hands-on packaged report-intake cancel, cleanup, retry, receipt, and delete sequence
   owned by [#965](https://github.com/teamleaderleo/preflight/issues/965).

Keep this sequence synchronized with #652 instead of extending it from open engineering issues. A
private rehearsal, a green source tree, or a complete candidate does not by itself authorize a
release tag or publication; those remain separate owner decisions.

## 1. Finish signing setup and the private rehearsal

Configure the `release-signing` Environment and the secrets/configuration required by the signed
Distribution path, then run the private three-platform rehearsal from current accepted source. The
rehearsal proves that the signing and package machinery can produce and exercise a candidate before
source is frozen.

The repository intentionally has no current `main`/tag ruleset. [#607](https://github.com/teamleaderleo/preflight/issues/607)
is closed `not planned` under that owner-selected policy. Before any tagged deployment is approved,
the `release-signing` Environment remains the human admission boundary: verify that the tag/commit is
the intended frozen accepted `main` identity.

**Exit:** #720's private rehearsal and signing-secret cleanup are accepted, with no demonstrated
source blocker promoted by the result.

## 2. Freeze one immutable candidate generation

Refresh `main`, #652, the candidate-evidence owners, and the open PR queue after the private rehearsal.
If no demonstrated release blocker remains, record one accepted source revision and stop admitting
unrelated source changes. When separately authorized, produce the tagged final candidate or preserved
Distribution package identity from that frozen revision.

A source change after freeze creates a new candidate generation and invalidates affected
package-dependent receipts. A checkout rebuild or an earlier rehearsal does not inherit the final
candidate's evidence.

**Exit:** one source identity owns the candidate bytes that the remaining acceptance work will use.

## 3. Exercise the native Windows package

Install the exact frozen package on native Windows with licensed Starsector. Exercise installation
selection, setup/preparation, two launches, campaign/combat, adapter health and fallback, ordinary
recovery, and removal using the candidate that publication would expose.

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
upload, cancel after a bounded partial transfer, prove cleanup and local ZIP retention, retry the
same ZIP, validate the accepted size/SHA and persisted receipt, then delete the uploaded case and
verify the required local/remote cleanup.

**Exit:** #965's final hands-on candidate sequence is accepted.

## After candidate acceptance

Finalize release notes and public copy from the accepted candidate evidence. Attach the reviewed
checksums, dependency/SBOM material, license and notices, privacy and limitation text, and platform
install/removal guidance to that same release generation. Publication remains an explicit owner
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
records a concrete candidate failure that changes release priority.

## Historical roadmap

The 2026-08-11 version is retained as a dated snapshot of the earlier release plan. It contains a
superseded Fractal-response gate and older sequencing, so use it only as historical context:

[Historical 2026-08-11-era snapshot](https://github.com/teamleaderleo/preflight/blob/6bee58e44264d222fded7ad51c04caa013d360be/docs/beta-roadmap.md)
