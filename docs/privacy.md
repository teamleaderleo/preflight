# Privacy

Preflight runs locally. Preparation, launching, profiles, settings, storage cleanup, diagnostics,
and benchmarking don't send data to the project maintainer.

Packaged builds make one routine network request that the user doesn't start: the update check
described below. Everything else leaves the machine only when the user asks for it.

The desktop build can save a diagnostics ZIP chosen by the user. A build compiled with the private
intake origin also exposes **Send run report**. Nothing is sent until the user opens its review,
sees the fixed inclusion and exclusion boundary, exact entries, finished ZIP byte count and
SHA-256, and confirms the send. Ordinary development and source builds omit the origin, so sending
is disabled while local export remains available.

## The update check

A packaged desktop build checks once per app session whether a newer signed release exists. It runs
the first time Preflight has a usable Starsector installation, so it doesn't run while discovery is
still going, while the game is launching or running, or when no installation was found. It gives up
after 30 seconds.

The request is an ordinary HTTPS GET of one fixed file on the project's public release feed:

```
https://github.com/teamleaderleo/preflight/releases/latest/download/latest.json
```

That address is compiled in and validated before use. The check refuses to run against anything that
isn't an absolute HTTPS URL, and refuses credentials, a query string, or a fragment outright, so no
build can attach a version, machine, or install identifier to it. The URL is the same for every
user. Preflight compares the versions locally and uploads nothing; whether an update is available is
decided on the machine.

GitHub, as the host, necessarily sees the ordinary metadata of any HTTPS request — source address,
client identifier, and time — exactly as it would for a manual download of the same file. Preflight
adds nothing to it.

A build without a compiled updater verification key, which is every ordinary development and source
build, makes no request at all and reports that verified updates aren't configured. On Linux the
check is limited to the AppImage; other packages are left to the package manager that installed
them.

Downloading and installing an update is a separate, explicit action. The download is verified
against the compiled signing key, and a failed download or verification leaves the installed version
in place.

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
