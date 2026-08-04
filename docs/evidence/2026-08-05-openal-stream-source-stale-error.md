# Starsector checks the wrong OpenAL error after creating a music source

**Date:** 2026-08-05

**Install:** Starsector 0.98a-RC8 macOS, exact bundled `fs.sound_obf.jar`

**Status:** exact repair passes executable and installed-archive gates; live pilot pending.

## Live symptom

The operator heard a short audio pop around startup and shutdown. The retained pilot
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

The next combined pilot must show both this repair and the GraphicsLib settings cache installed. A
nonzero `recoveredStaleErrors` count with zero `generationErrors` would directly prove that the
previous false failure was prevented. Whether it removes the audible shutdown pop remains an
operator observation; the logged bug directly explains only the startup failure-and-retry.
