# Privacy

Preflight runs locally. Preparation, launching, profiles, settings, storage cleanup, benchmarking,
and ordinary diagnostics stay on the machine. Network activity comes from two separate features:
update checks, when enabled, and support-report sending, when a package has the intake service
configured and the user chooses to send a report.

Local maintenance keeps the 10 newest launch reports and 5 newest benchmarks while the desktop is
open and idle, removing older Preflight diagnostics without making a network request or touching
Starsector, mods, saves, screenshots, or game settings.

## Update checks

A packaged desktop app with the updater verification key can check the configured release feed after
Preflight is ready. The public release feed is hosted through GitHub Releases, and the user can also
ask for a manual check from the desktop.

Settings can turn the automatic check off. With **Check for updates automatically** cleared, startup
does not make that request; the manual check remains available when the user asks for it. The
preference itself is stored locally.

An update check requests release metadata. Preflight does not attach a diagnostics ZIP, enabled-mod
list, benchmark result, machine name, email address, account identifier, or persistent client ID to
that request. As with any network request, the remote service receives ordinary connection metadata
such as the source IP while serving it; for the default feed, that service is GitHub.

Finding a newer version does not install it. The user reviews the offer and chooses **Install and
restart**; Preflight checks the offer again, downloads it, and verifies the updater signature before
installation. A failed download or verification leaves the installed version available.

The updater is disabled in development packages that lack its verification key. On Linux, the
built-in updater applies to the AppImage path, while `.deb` installations continue through the
package manager. The standalone JAR has no desktop background updater.

## Support reports

**Copy setup** is the lightweight public-support option and stays local: it produces useful game,
profile, mod, and launch facts while leaving out private paths, credentials, saves, and arbitrary
logs.

For deeper diagnostics, **Help → Make a support file** creates a ZIP locally. A release package with
the private intake origin configured can also offer **Send** after the user opens the review. Source
and ordinary development packages omit that origin, so they can create the file without sending it.

Before a report is sent, the desktop shows the finished ZIP path, size, SHA-256, included entries,
and exclusions. Sending is a separate action and supports cancellation and retry.

### What the support ZIP can contain

The ZIP contains a disclosure, a manifest, and a fixed set of JSON or JSONL diagnostics from selected
launch runs and benchmark sessions. Depending on the available evidence, that can include enabled
mod IDs, platform and runtime details, adapter targets, counters, hashes, resource names, benchmark
settings, and failure metadata. Occurrences of the current user home are replaced with `<home>`.

The archive excludes acceleration caches, Starsector and mod files, saves, ordinary logs and crash
dumps, JFR recordings, screenshots, audio, unknown filenames, symbolic links, and binary content.
Individual source files above 512 KiB and a total source set above 5 MiB are excluded as well. The
format and detailed inclusion rules are documented in [Diagnostics export](diagnostics.md).

### What sending adds

Creating a support case sends the Preflight version, ZIP byte count, and ZIP SHA-256 to the intake
service; after confirmation, the service receives that ZIP. Cloudflare processes ordinary network
metadata such as the source IP while serving and rate-limiting the request. Preflight does not add a
user ID, advertising ID, machine name, email address, account, or persistent client secret.

Accepted reports are stored in a private Cloudflare R2 bucket. The intended default retention starts
automatic deletion after 14 days, with up to another day for R2 lifecycle processing; the receipt
states the retention deadline. The receipt also carries case-specific authorization that can request
earlier deletion. Reports are not sold or used for advertising.

The intake service and desktop send/delete path are implemented and have completed packaged macOS
canary exercise against the private production bucket. Public report sending stays disabled until the
final release candidate completes its packaged cancel/retry/delete canary. The service contract is in
[report-intake/README.md](../report-intake/README.md).

**Run-report service operator:** the Preflight project maintainer (`teamleaderleo`).  
**Contact:** [the Preflight issue tracker](https://github.com/teamleaderleo/preflight/issues). Keep
private diagnostics, credentials, personal data, and security exploit details out of public issues;
use the support flow and [security policy](../SECURITY.md) when appropriate.  
**Privacy notice effective date:** 2026-08-13.

Automatic failed-run reporting starts off and requires a separate remembered choice. When enabled,
it can send the same support ZIP after a failed Preflight launch whose stored run/process identity
still matches the failure being reported. At most three local automatic-report ZIPs are retained.
Ordinary launch, preparation, and internal observability cannot enable this setting on their own.
