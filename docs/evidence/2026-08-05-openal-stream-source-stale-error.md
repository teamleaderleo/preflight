# Starsector checks the wrong OpenAL error after creating a music source

**Date:** 2026-08-05

**Install:** Starsector 0.98a-RC8 macOS, exact bundled `fs.sound_obf.jar`

**Status:** exact repair passes executable, installed-archive, full verification, and live gates.

## Live stale-error symptom

The retained pilot
`graphicslib-hot-settings-v1-20260805-040708` gives a concrete startup failure:

```text
33653 [Thread-7] WARN ... Error initializing music source - AL error 40963
34169 [Thread-7] INFO sound.oo0O - Creating streaming player for music with id [miscallenous_main_menu.ogg]
34171 [Thread-7] INFO sound.OooO - Playing music with id [miscallenous_main_menu.ogg]
```

40963 is OpenAL `AL_INVALID_VALUE`. The first player construction failed, then the next attempt
succeeded 516 milliseconds later. There was no decoder failure, and later music transitions played
normally.

## Exact vanilla bug

The installed `sound.oo0O(List, String)` bytecode does this:

```java
int error = AL10.alGetError();
AL10.alGenSources(buffer);
if (error == 0) {
    // initialize the source
}
if (error != 0) {
    throw new RuntimeException("Error initializing music source - AL error " + error);
}
```

`alGetError()` returns and clears the error from an earlier OpenAL operation. The constructor then
attributes that stale value to `alGenSources`, never asks OpenAL whether source generation itself
failed, and aborts a valid source whenever unrelated earlier work left an error behind.

## Exact repair

`audio-stream-source-error-order-v1` keeps the first `alGetError()` as the required stale-error
clear, then reads the error again immediately after `alGenSources` and leaves vanilla's branches to
use that actual result. Telemetry records attempts, nonzero stale errors, real generation errors,
and stale errors whose following generation succeeded.

The adapter transforms only the exact reviewed class hash from the exact bundled sound archive,
Java 17 class version, app classloader, and constructor. It also reviews the one-pre-read,
one-generation, retained-error-local instruction shape. Any drift retains the original bytes.

## Verification before launch

- an executable woven fixture proves stale `AL_INVALID_VALUE` plus successful generation no longer
  throws;
- the same fixture proves a real post-generation OpenAL error still takes vanilla's failure branch;
- wrong hash, changed generation call, and second rewrite fail closed;
- the installed-archive test confirms the exact real constructor now has one error read before and
  one after its sole `alGenSources` call;
- focused GraphicsLib/audio tests pass together.

## Live result

The combined pilot `graphicslib-audio-v2-20260805-041804` exited normally with ACTIVE adapter
health, 31 transformations, zero fallback, and zero contained failure. The repair reported:

| counter | value |
| --- | ---: |
| source-construction attempts | 202 |
| stale pre-generation errors | **1** |
| actual generation errors | **0** |
| stale errors followed by successful generation | **1** |

This is direct same-shape evidence: the stale error occurred again, but the immediately following
`alGenSources` succeeded. There was no `Error initializing music source` log, and main-menu music
created and played on the first recorded attempt at 34.242/34.243 seconds rather than failing and
retrying 516ms later. The repair prevented the false startup failure without hiding a real
generation error. Whether it also removes the audible shutdown pop remains an operator observation;
the logged bug directly explains the startup failure-and-retry.

## Separate in-process transition pop

The operator subsequently localized the audible pop more precisely: loading a campaign, entering
and leaving a combat simulation, and leaving the refit screen. These are music-state transitions
inside one running process, not OpenAL device startup and shutdown. The
`aitweaks-range-v1-20260805-043049` log aligns all reported boundaries with creation, resumption, or
cleanup of main-menu, campaign, and battle-ambience streaming players. It contains no OpenAL error.

Exact sound bytecode shows that Starsector already begins a new player at zero logical gain and
fades it in. An old player is advanced until its logical fade scalar reaches zero before the music
thread closes its decoder, deletes the OpenAL source, and selects the pending player. This rules out
a simply missing game-level fade, but the ordinary log does not retain requested fade duration or
the final scalar at native source destruction.

The bundled macOS `openal.dylib` identifies itself as OpenAL Soft 1.23.1 and uses CoreAudio. Upstream
OpenAL Soft documents click/pop prevention for prematurely stopped sources in 1.21.1, so merely
assuming that any newer library fixes this exact case is not supported. The OpenAL 1.1 API also
defines source state changes but does not promise that a host-side gain update and immediate source
deletion become sample-synchronous at the output device.

The exact sound-class transform now carries a passive `audio-music-transition-probe-v1`. It records
player creation, the four requested fade variants and durations, and cleanup with the game's final
fade scalar, fade-out duration, source ID, and monotonic ordering. It makes no OpenAL query or
mutation. Its executable fixture and exact installed-archive test pass. A live follow-up is pending;
no timing workaround or native-library replacement is enabled before that evidence.
