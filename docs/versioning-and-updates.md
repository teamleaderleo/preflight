# Versioning and updates

A Starsector or mod update does not turn Preflight into one giant compatibility switch. Prepared work
and runtime shortcuts each check the inputs they depend on, so a changed mod can make one shortcut
step aside while unrelated prepared data keeps working; a larger game, launcher, runtime, or
preference change can still require a new Preflight release.

There are three identities underneath that behavior:

1. **Application version:** the desktop host, CLI, Java agent, adapter catalog, and bundled runtime
   shipped together as one SemVer release.
2. **Compatibility identity:** the game, mod archive, class, loader, and method fingerprints a
   particular runtime optimization recognizes.
3. **Profile identity:** the selected installation, ordered mods, source content, preparation policy,
   and cache-format inputs used for reusable prepared data.

Most players only need the first paragraph. The identities explain why one changed target can lose
one optimization without requiring every cache and every other shortcut to be discarded.

## Application updates

Supported desktop packages check one configured HTTPS release feed after setup and whenever the user
asks. A newer version appears on Home and Settings with its notes and an **Install and restart**
action; Preflight does not install an update while Starsector, preparation, or another update is
running.

Before downloading, the app checks the offer again and requires the version, target, URL, signature,
notes, and date to match what the user reviewed. The downloaded artifact then has to pass the updater
signature embedded in the installed app. A withdrawn offer returns to the update check, and a failed
download, verification, or installation leaves the current version available.

The public release verifier accepts updater assets only from this repository's release path for the
matching `v<version>` tag. Private candidates use a separate inert origin, so candidate metadata
cannot quietly redirect an install to an unrelated HTTPS host or release tag.

The built-in updater covers macOS, Windows, and Linux AppImage packages. Debian `.deb` installations
stay with the package manager that installed them. The standalone JAR has no desktop background
updater.

Updates replace the application package, not the player's caches, profiles, diagnostics, or game
files. Old release packages and their checksums remain the rollback path; Preflight does not try to
keep several application versions installed side by side.

## Compatibility across releases

Application updates keep the Preflight home directory and rediscover the profiles and reusable data
inside it. Cache files carry format versions, and readers reject formats they do not understand. If a
new release needs an incompatible representation, it moves that representation into a new namespace
instead of rewriting every older cache in place.

That choice is mostly about rollback. An older Preflight package can still find the data format it
understands after the newer application has run, and the older namespace remains visible as Preflight
data until the user chooses broader cleanup. A format-changing update can therefore use extra disk
for a while because old and new representations may coexist.

Named mod profiles are user data. A future profile-schema change needs a reader or migration that
preserves the previous file until the replacement has been accepted.

## When a Preflight update is needed

A new application release can be needed when game or mod code changes a reviewed runtime target;
when launcher, JVM, graphics, audio, discovery, or preference behavior changes; when an optimization
or its dependencies change; when a cache format changes incompatibly; when the desktop, updater,
report intake, security behavior, or bundled runtime changes; or when a known-bad compatibility match
needs to be removed.

New runtime transformation logic stays inside signed application releases. The first beta does not
use a second remote code-delivery feed for adding transformations. A future signed advisory feed may
be useful for narrowing or disabling a known-bad compatibility match, but it should remain a way to
remove permission from existing behavior, not a second mechanism for installing new executable
logic.

## Signing-key changes

Updater-key rotation requires a transition release signed by the old key and carrying the new public
key. If the private updater key is suspected compromised, suspend the feed; a client cannot establish
a trustworthy new signing root from a key an attacker may already possess. Recovery then uses a
manually downloaded package and its independently published checksum.

For release-operator details, use [Release signing setup](release-signing-setup.md). For the player
rollback path, see [Rollback and bad-release response](rollback.md).
