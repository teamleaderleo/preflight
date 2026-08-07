# Rollback and bad-release response

## Immediate user path

Stop the game before changing Preflight. Select **Off / troubleshooting** in the desktop app, or use:

```bash
java -jar preflight.jar run --optimization-preset off
```

Off retains process ownership and bounded outcome reporting without installing runtime adapter
transformations. Record whether the problem still occurs. Keep caches in place; content-addressed
data doesn't need to be deleted to disable its readers.

If the application release itself is broken, reinstall the preceding signed package after checking
its published SHA-256. Debian installations should use their package manager. Replacing Preflight
doesn't require editing Starsector, its mods, or saves. A failed updater download or signature check
leaves the installed version in place.

## Maintainer response

1. Reproduce with the reported product, game, mod, preset, and storage identities. Ask for a private
   run-report case ID before asking for any broader data.
2. Confirm whether Off removes the problem. Identify the exact accepted target fingerprint and
   adapter health record when it does.
3. Publish one notice in the affected GitHub release and a pinned issue. Link the same notice from
   any forum or Reddit release post instead of creating different instructions.
4. Remove the bad release from the updater's latest path when continued installation is riskier
   than an unavailable update. Existing installations remain runnable; no unsigned replacement is
   published.
5. Ship a signed patch that removes or narrows the accepted fingerprint, defaults the affected plan
   off where needed, and retains the original behavior as fallback. Exercise install, update, Off,
   rollback, and the affected game scenario before restoring the update feed.

## Notice template

> Preflight [version] has a confirmed issue affecting [exact game/mod/platform identity]. Select
> **Off / troubleshooting** before launching. Existing Starsector files, mods, and saves don't need
> to be changed. [Previous version] remains available at [release link] with SHA-256 [digest]. The
> updater won't offer the affected build while [patch version] is verified. Report whether Off
> changes the result and include only an optional private run-report case ID in the public issue.

The incident record should name the first affected version, fixed version, exact fingerprint scope,
observable symptom, fallback behavior, verification performed, and whether any accepted private
reports require early deletion.
