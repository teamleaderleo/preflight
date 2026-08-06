# Fractal Softworks permission request draft

**Maintainer correspondence draft — not public release copy.** Send to Fractal Softworks through
the contact channel they currently publish, then retain the reply and any conditions outside the
public repository unless they authorize publication.

## Subject

Permission request for an open-source Starsector performance launcher/tool

## Message

Hi Alex,

I have been developing a free, open-source external tool called **Preflight**. It is described as an
unofficial performance launcher for Starsector.
It is intended to make repeat launches of heavily modded Starsector faster and to improve a small
set of measured campaign/combat hotspots.

The tool doesn't distribute Starsector or mod content and doesn't permanently patch game or mod
JARs, executables, assets, activation data, or saves. It launches the user's existing installation
with a process-local Java agent. Runtime changes are exact-version-gated, happen only in the child
JVM's memory, and fall back to the original code when an identity or validation doesn't match.
Prepared caches and reports live in the tool's own data directory. The only game-owned settings it
can change are explicit, backed-up mod-profile and ordinary launcher/gameplay preference actions.

Development involved inspecting the shipped bytecode and runtime behavior so the integrations could
be narrow and fail safely. Before distributing anything publicly, I would like your written guidance
on four points:

1. Are you comfortable with free public distribution of this external launcher/preparation tool?
2. Are you comfortable with the runtime instrumentation and compatibility-analysis approach
   described above?
3. Are you comfortable with the project using **Starsector** descriptively in phrases such as
   "a performance launcher for Starsector" while its name remains **Preflight**?
4. Is there a disclaimer, attribution, technical boundary, or other condition you would like the
   project to follow?

I can send the source repository, exact technical boundary, screenshots, and a private build for
review. I won't publish binaries or announce a beta until I hear back.

Thanks,

[NAME]

## After receiving a reply

- Record the date, scope, descriptive-name guidance, required disclaimer/attribution, and any
  technical or distribution conditions in the private release record.
- Convert every condition into a blocking item in [release-readiness.md](release-readiness.md).
- Don't summarize silence, ambiguity, or a narrow answer as broader permission.
