# Core sound paths repeatedly miss Java's class-relative lookup

Date: 2026-08-05

Status: implementation, exact installed recovery execution, and a clean normal live run complete

## Live failure

Run `campaign-call-times-v1-20260805-070642` completed normally, but its final 1 MiB console ring
contained 731 `sound.ooOO` load errors. Of those, 403 named `laser_loop.ogg` and 244 named
`maneuvering_jets_loop.ogg`. Prepared-audio telemetry recorded 6,242 failures after intercepting
2,050 loads and serving 2,049 prepared buffers. These failures occurred before decoding because
the supplied input stream was null. The referenced files exist in the installed game resources.

Previous live pilots reported zero prepared-audio failures, so this is an intermittent recovery
case rather than part of the accepted audio preparation baseline. Repeated stack traces and failed
lookups during combat are also a plausible source of late frame and audio disruption even when the
game substitutes an empty sound instead of crashing.

## Exact cause

Installed `sound/ooOO.class` has SHA-256
`24a7e0107fd1c98168ba935b76f9141fdff6814eff43b819ae960a2a94a40147`; its owning
`fs.sound_obf.jar` has SHA-256
`79e5bc71236333541674e2b9093642ac5a2d68d9e55cb8a71f299fd389ba1573`.

All three string readers—`Ò00000`, `Object`, and `o00000`—contain exactly one call to
`Class.getResourceAsStream(String)`. Callers provide paths such as `sounds/sfx/...` without a
leading slash. Java therefore resolves them relative to the owning package as
`sound/sounds/sfx/...`, which is absent, rather than from the classpath root where the resource is
present. The shipped method catches the later I/O failure and returns an empty sound, but does not
negative-cache the miss, so callers can repeat the lookup and error every frame.

## Narrow correction

Plan `audio-classpath-root-resource-fallback-v1` replaces only those three reviewed calls in the
exact installed class. Its runtime helper:

1. performs the shipped relative lookup first;
2. returns an original hit unchanged;
3. only after a null result for a relative path, retries `"/" + path` from the classpath root;
4. leaves absolute paths and final misses unchanged; and
5. contains a fallback runtime failure by returning the original null result, while still
   propagating VM-fatal errors.

This preserves normal resolution and lets the existing prepared-audio positive cache own decoded
buffer reuse. Telemetry reports original hits, fallback attempts, fallback hits, final misses, and
contained fallback failures.

## Verification

Runtime tests cover original hits, root fallback hits, final misses, absolute-path behavior, and
session telemetry. Shape tests reject altered class hashes or altered lookup counts. The installed
game archive test verifies all three exact methods transform to the public runtime entry point. It
then loads the transformed installed class in an isolated loader, invokes the actual recovery
method for `sounds/sfx_interface/ui_button_mouseover.ogg`, and proves the loose installed resource
is returned through one classpath-root fallback hit without opening OpenAL.

Full repository `mvn verify` passes. Live run
`audio-root-fallback-v1-20260805-071953` then completed two combat simulations and normal shutdown
with all 40 transforms active, no declines, no contained failures, zero prepared-audio failures,
and none of the previous `sound.ooOO` retry errors. The fallback counter remained zero because the
intermittent sound-cache recovery path did not occur in that run. Therefore the live comparison is
a no-regression and clean-normal-path result, not a claim that the adapter caused the failures to
disappear. The deterministic installed-class execution supplies the positive recovery-path proof.
