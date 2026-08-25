# Privacy

Preflight runs locally. Preparation, launching, profiles, settings, storage cleanup, diagnostics,
and benchmarking don't send their contents to the project maintainer. Packaged desktop builds can
make the update-check request described below, and run reports are sent only through the separate
explicit-consent path described later in this document.

Local maintenance keeps 10 launch reports—including the latest completed paired comparison and
save/reload check for its recorded setup when available—and the 5 newest benchmark campaigns. It
removes older Preflight evidence while the desktop is idle. This is local deletion only; it makes
no network request and doesn't touch Starsector, mods, saves, screenshots, or game settings.

## Update checks

A packaged desktop build with a configured updater verification key quietly checks for signed update
metadata once after Preflight is ready. The user can also ask it to check again from the desktop.
The default public feed is the Preflight release metadata hosted by GitHub Releases; a build may use
a different HTTPS endpoint only when that endpoint was selected at compile time.

Settings discloses this check and turns it off. With **Check for updates automatically** cleared,
the startup check does not run and the manual button beside it is the only one that does. The
preference is stored locally and sends nothing when it changes.

An update check requests release metadata only. It doesn't send a diagnostics ZIP, enabled-mod list,
benchmark result, machine name, email address, account identifier, or persistent Preflight client
identifier. The service serving the configured endpoint necessarily receives ordinary network
metadata such as the source IP as part of delivering the request. For the default feed, GitHub is
the remote service handling that request.

Finding an update does not download or install it. Installation starts only after the user chooses
the explicit **Install and restart** action. Preflight then rechecks that the exact signed offer is
still current before downloading and verifying the update package. A failed download or signature
verification leaves the installed version unchanged.

The built-in updater is disabled in development builds that don't contain an updater verification
key. On Linux it is available to the AppImage build; other Linux packages, including the `.deb`,
defer updates to the package manager used to install them. The standalone JAR has no desktop
background updater.

## Voluntary support ZIPs

The desktop build can save a diagnostics ZIP chosen by the user. A build compiled with the private
intake origin also enables sending. Nothing is sent until the user chooses **Get support**, creates
the ZIP, opens its review, sees the fixed inclusion and exclusion boundary, exact entries, finished
byte count and SHA-256, and confirms the send. Ordinary development and source builds omit the
origin, so sending is disabled while local export remains available.

### What a support ZIP contains

The report is the same bounded ZIP produced by `preflight evidence export`. It contains a disclosure,
a manifest, and allowlisted JSON or JSONL evidence from selected launch runs and benchmark sessions.
That evidence can include enabled mod IDs, platform and runtime details, adapter targets, counters,
hashes, resource names, settings used by a benchmark, and bounded failure metadata. Occurrences of
the current user home are replaced with `<home>`.

It excludes acceleration caches, Starsector and mod files, saves, logs and crash dumps, JFR
recordings, screenshots, audio, unknown filenames, binary content, symbolic links, files above 512
KiB, and source content above 5 MiB. The exact format is documented in
[Diagnostics export](diagnostics.md).

### What sending adds

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
[report-intake/README.md](../report-intake/README.md).

**Run-report service operator:** the Preflight project maintainer (`teamleaderleo`).
**Contact:** [the Preflight issue tracker](https://github.com/teamleaderleo/preflight/issues). Do not
post private diagnostics, credentials, personal data, or security exploit details in a public issue;
use the bounded support flow and [security policy](../SECURITY.md) as applicable.
**Privacy notice effective date:** 2026-08-13.

The first beta does not send failed-run reports automatically. A support ZIP is sent only after you
review it and press Send in Help. Ordinary launch, preparation, and Worker observability can't send
one.
