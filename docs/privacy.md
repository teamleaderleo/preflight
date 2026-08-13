# Privacy

Preflight runs locally. Preparation, launching, profiles, settings, storage cleanup, diagnostics,
and benchmarking don't send data to the project maintainer.

The desktop build can save a diagnostics ZIP chosen by the user. A build compiled with the private
intake origin also exposes **Send run report**. Nothing is sent until the user opens its review,
sees the fixed inclusion and exclusion boundary, exact entries, finished ZIP byte count and
SHA-256, and confirms the send. Ordinary development and source builds omit the origin, so sending
is disabled while local export remains available.

## What a run report contains

The report is the same bounded ZIP produced by `preflight evidence export`. It contains a disclosure,
a manifest, and allowlisted JSON or JSONL evidence from selected launch runs and benchmark sessions.
That evidence can include enabled mod IDs, platform and runtime details, adapter targets, counters,
hashes, resource names, settings used by a benchmark, and bounded failure metadata. Occurrences of
the current user home are replaced with `<home>`.

It excludes acceleration caches, Starsector and mod files, saves, logs and crash dumps, JFR
recordings, screenshots, audio, unknown filenames, binary content, symbolic links, files above 512
KiB, and source content above 5 MiB. The exact format is documented in
[Diagnostics export](diagnostics.md).

## What sending adds

Creating a case sends the Preflight version, ZIP byte count, and ZIP SHA-256 to the intake service.
After confirmation, the service receives that exact ZIP. Cloudflare necessarily processes normal
network metadata such as the source IP to serve and rate-limit the request. Preflight doesn't add a
user identifier, advertising identifier, machine name, email address, account, or persistent client
secret.

Accepted reports are stored in a private Cloudflare R2 bucket. The proposed default starts automatic
deletion after 14 days; R2 lifecycle processing can take up to another day, which is reflected in the
receipt's retention deadline. The receipt also carries a case-specific deletion authorization so the
user can request earlier deletion. The report isn't used for advertising or sold.

The intake service and desktop consent/upload/delete path are implemented and have completed a
packaged macOS canary against the private production bucket. Public packages remain disabled until
the final release candidate repeats that path. Its operational contract is in
[report-intake/README.md](../report-intake/README.md). This document will name the service operator,
contact address, and effective date before public submission is enabled.

Automatic background diagnostics are off. A future automatic-crash option would require a separate,
remembered, default-off consent choice; enabling ordinary Worker observability can't turn it on.
