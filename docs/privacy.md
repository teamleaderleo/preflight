# Privacy

Preflight runs locally. Preparation, launching, profiles, settings, storage cleanup, diagnostics,
and benchmarking don't send their contents to the project maintainer. The first beta can make the
signed update-check request described below, but it does **not** contain an active remote diagnostics
intake: support ZIPs remain on the player's computer until the player chooses a separate support
path to share one.

Local maintenance keeps the 10 newest launch reports and 5 newest benchmarks and removes older
Preflight evidence while the desktop is idle. This is local deletion only; it makes no network
request and doesn't touch Starsector, mods, saves, screenshots, or game settings.

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

The desktop build can save a diagnostics ZIP chosen by the user. In the first beta, creating the ZIP
is the end of Preflight's support-sharing capability: the app exposes no remote review/send/delete
action and no automatic failed-run upload. The player can inspect the disclosure inside the ZIP and,
if they choose, share the file later through a private support path they select themselves.

The first-beta release build rejects a configured `PREFLIGHT_REPORT_INTAKE_ORIGIN`, and the trusted
Distribution workflow supplies no report-intake origin. Stale development-era automatic-report
preferences cannot enable network sending in this build. An authoritative local-only intake status
also clears stale renderer-side report consent/receipt state so it cannot become an actionable
remote control in the beta.

### What a support ZIP contains

The ZIP is the same bounded archive produced by `preflight evidence export`. It contains a
disclosure, a manifest, and allowlisted JSON or JSONL evidence from selected launch runs and
benchmark sessions. That evidence can include enabled mod IDs, platform and runtime details,
adapter targets, counters, hashes, resource names, settings used by a benchmark, and bounded failure
metadata. Occurrences of the current user home are replaced with `<home>`.

It excludes acceleration caches, Starsector and mod files, saves, logs and crash dumps, JFR
recordings, screenshots, audio, unknown filenames, binary content, symbolic links, files above 512
KiB, and source content above 5 MiB. The exact format is documented in the
[Diagnostics export guide](https://github.com/teamleaderleo/preflight/blob/main/docs/diagnostics.md).

### Deferred remote reporting

The repository contains a private report-intake service and dormant desktop transport code from
pre-release experiments. Those paths are **not enabled in the first beta**. The beta does not create
a remote case, upload a support ZIP, retain a remote report on the player's behalf, issue a deletion
bearer, or send a failed-run report automatically.

A later release may reintroduce remote reporting only after its authority, retention, deletion,
migration, consent, package, and privacy contracts are reviewed for that release. Any future
remote-capable release must require its then-current consent contract; a stale beta/development
preference cannot silently opt a player into sending.

Historical intake/canary implementation notes remain under `report-intake/` for engineering review;
they do not describe a capability of the first-beta package.

**Project operator:** the Preflight project maintainer (`teamleaderleo`).
**Contact:** [the Preflight issue tracker](https://github.com/teamleaderleo/preflight/issues). Do not
post private diagnostics, credentials, personal data, or security exploit details in a public issue;
use a private support path for a diagnostics ZIP and the repository's
[security policy](https://github.com/teamleaderleo/preflight/blob/main/SECURITY.md) as applicable.
**Privacy notice effective date:** 2026-08-19.
