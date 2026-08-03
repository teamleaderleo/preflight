# GraphicsLib compact replay adapter live gate

**Date:** 2026-08-04  
**Install:** Starsector 0.98a-RC8, 89 enabled mods, GraphicsLib 1.12.1  
**Protocol:** direct `--fast` launch with `--graphicslib-compact-replay`, startup phase probe, no
JFR recording, automatic SIGTERM after the ordinary main-menu marker

The compact replay itself was previously installed as a test build and measured at the exact
GraphicsLib callback: 8.503s to 5.465s, a 3.038s direct reduction. This gate tests Preflight's
non-invasive delivery of the same reviewed class replacement without modifying Graphics.jar.

## First live attempt: safe decline

The initial live launch reached the main menu and completed normally, but adapter health reported a
partial application and retained GraphicsLib's original class. The target class hash, archive
suffix, mod source kind, and URLClassLoader all matched; only archive hashing was unavailable.

The live loader supplied this valid local URL:

```text
file:/Applications/Starsector.app/Contents/Resources/Java/../../../mods/zz GraphicsLib-1.12.1/jars/Graphics.jar
```

Because its space was not percent-escaped, `URL.toURI()` rejected it before the existing exact
archive hash could run. This was a gate compatibility issue, not class drift or a replacement
failure.

Commit `0629a74` keeps the ordinary URI path first, then rebuilds only malformed local `file:` URLs
from URI components so the path is escaped by Java. Non-file URLs and paths that remain malformed
still have no local path and fail closed. A regression test reproduces the live unescaped-space URL
and requires the exact resolved archive bytes to be hashed.

## Successful live application

The corrected cold and cooled-warm Janino pilot launches both applied the GraphicsLib replacement
exactly once:

- exact TextureData class SHA-256:
  `6a4302bcacd2dd90f6637c815d1443ddfdb3d28ff59095d48c875358de4e8594`;
- exact Graphics.jar SHA-256:
  `832064013fe853731941e547842884ba121fb8b20eff08d24137f7a2c916903a`;
- source kind `MOD`, suffix `graphics.jar`, loader `java/net/URLClassLoader`;
- `applications: 1`, no mismatch, decline, shadow, or contained failure;
- adapter health `ACTIVE`, wrapper outcome `COMPLETED`, exit 0, no fatal lifecycle evidence;
- ordinary main-menu marker observed before automatic clean shutdown.

The retained runs are:

- `~/.starsector-preflight/runs/janino-graphics-timed-cold-20260804-025551`
- `~/.starsector-preflight/runs/janino-graphics-timed-warm-cooled-20260804-030033`

The replacement was enabled in both halves, so these launches are applicability and compatibility
evidence rather than another performance comparison. The earlier exact callback A/B remains the
performance evidence.

Full `mvn verify` passes, including the installed Graphics.jar transform integration test. The live
adapter remains explicit and retains original bytes on every class, archive, source-kind, loader,
or replacement-resource mismatch.
