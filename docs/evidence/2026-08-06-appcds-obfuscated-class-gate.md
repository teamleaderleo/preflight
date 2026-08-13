# Dynamic AppCDS is incompatible with the shipped obfuscated game classes

**Date:** 2026-08-06
**Install:** Starsector 0.98a-RC8, bundled x86-64 Zulu 17 on macOS/Rosetta
**Profile:** current 83-mod `--fast` profile

The repository's exact-JVM AppCDS capability detector proves that the runtime can create and
consume a small ordinary application archive. That does not imply that the same JVM can archive
Starsector.

Two unattended, automatically stopped direct-launch diagnostics tested the real boundary. Plain
`-XX:ArchiveClassesAtExit` refused to start because Preflight is a Java agent:

```text
Error occurred during CDS dumping
Must enable AllowArchivingWithJavaAgent in order to run Java agent during CDS dumping
```

The second diagnostic enabled HotSpot's corresponding diagnostic flag. HotSpot warned that the
result is for testing only, forced verification of every non-system class during the dump, and then
rejected Starsector's shipped obfuscation before resource initialization:

```text
All non-system classes will be verified (-Xverify:remote) during CDS dump time.
java.lang.ClassFormatError: Illegal field name "for.Object" in class
com/fs/starfarer/settings/StarfarerSettings
```

The launcher's existing `-noverify`, `-XX:-BytecodeVerificationLocal`, and
`-XX:-BytecodeVerificationRemote` flags cannot preserve the game's ordinary behavior because
dynamic dumping itself forces the verification mode. The failed process applied no reviewed
transformations, loaded no game state, and left no JVM. Its 4.4MB testing archive was deleted.

Do not integrate `ArchiveClassesAtExit`/`SharedArchiveFile` for this game build. Revisit only if a
future Starsector build has verifier-valid classes and the exact runtime no longer marks
agent-assisted archives testing-only. The generic capability detector remains useful and correct;
the real application's bytecode is the failed gate.
