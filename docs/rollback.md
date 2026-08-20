# Rollback and bad-release response

## Immediate user path

If an optimized launch behaves differently, stop Starsector and select **Off / troubleshooting** in
the desktop app, or use:

```bash
java -jar preflight.jar run --optimization-preset off
```

Off removes the runtime optimization layer while keeping the launcher, process handling, profiles,
settings, and support tools available. Record whether the problem still happens; that comparison is
usually more useful than deleting caches or reinstalling everything first.

Keep prepared data in place while testing Off. Disabling the runtime readers does not require
throwing the cache away, and retaining it leaves more evidence available if the problem needs
investigation.

If the Preflight release itself is broken, reinstall the preceding release package after checking
its published SHA-256. Debian installations should use their package manager. Replacing Preflight
does not require editing Starsector, its mods, or saves, and a failed updater download or signature
check leaves the installed version available.

## Maintainer response

1. Reproduce with the reported product, game, mod, preset, and storage context. Ask for a private
   run-report case ID before asking for broader data.
2. Confirm whether Off removes the problem, then identify the affected runtime target and health
   record when it does.
3. Publish one notice in the affected GitHub release and a pinned issue. Link the same notice from
   any forum or Reddit release post instead of creating divergent instructions.
4. Remove the bad release from the updater's latest path when continued installation is riskier than
   temporarily having no update available. Existing installations remain runnable; do not publish an
   unsigned replacement.
5. Ship a signed patch that removes or narrows the affected compatibility match, defaults the plan
   off where needed, and preserves the original game behavior as fallback. Exercise install, update,
   Off, rollback, and the affected game scenario before restoring the update feed.

## Notice template

> Preflight [version] has a confirmed issue affecting [game/mod/platform scope]. Select **Off /
> troubleshooting** before launching. Existing Starsector files, mods, and saves do not need to
> change. [Previous version] remains available at [release link] with SHA-256 [digest]. The updater
> will stop offering the affected release while [patch version] is verified. Report whether Off
> changes the result and include only an optional private run-report case ID in the public issue.

The incident record should name the first affected version, fixed version, affected compatibility
scope, observable symptom, fallback behavior, verification performed, and whether any accepted
private reports require early deletion.
