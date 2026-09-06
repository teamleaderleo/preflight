# Linux prepared audio, native buffer guards, and full-menu timing

## Scope and machine state

The requested Windows review retained Preflight alone as the faster first-usability configuration.
Fast Rendering alone's previous graphics-preload marker is not evidence of a fully shown menu.
No new Fast Rendering startup bypass was enabled. Its loader owns upload policy, queued work,
registration, and shutdown; simply bypassing that initialization would lose required behavior.
The earlier decoder-only bridge was already a measured regression and remains off.

The Windows VM was shut down through its guest agent and verified off. Big Red's sole Arc GPU
remained bound to vfio-pci, with GDM inactive. A runtime handback returned PCI 0000:00:02.0 to
i915 and started the existing GDM/Leo auto-login configuration. An attempted xe bind declined
because this kernel does not support that device without force-probe; no forced driver was used.
Persistent VFIO boot settings were not modified. Linux now has the real GNOME desktop and
/dev/dri/renderD128. All 16 host CPUs are available; power profile is performance. No affinity or
priority restriction was added.

The installed Linux game is /home/leo/Games/starsector-0.98a-RC8. Tests retain its current mod set,
2048x1280 fullscreen geometry and reviewed Zulu 17.0.10/G1 launch policy with a 2 GiB heap. Sound
was initially disabled. Sound-enabled cache comparisons temporarily enable it through Preflight's
launch-settings command, then restore it. Preparation is outside startup measurements.

## Corrections and contracts

Audio preparation originally failed with NoSuchMethodException: Linux sound.J exposes super,
where the macOS binding expected o00000. The new binding pins Linux sound/J.class
b079051dc57de2708ad8c249b35b35469d3dc6cabbb255afdbfb713ea6bbaf8c and sound/F.class
5d03d4031ee2b7cac51ec6838730b91824489128fd3b538bcb41d2f6208b13e2. Runtime admission also pins
fs.sound_obf.jar c2d071f24b84e7227fced0a43d0f531f133b7d979d7c7784f66af11ce436cf21 and the
reviewed application loader. It accepts only exact ByteArrayInputStream inputs; custom streams
retain the original decoder. Prepared PCM must match source bytes, decoder policy, signed 16-bit
little-endian format, channels and rate. Misses/corrupt entries invoke the preserved original
method. Music, sound registration and OpenAL policy remain with the game.

Installed sound.Object uploads the returned buffer through LWJGL 2, whose wrapper converts the
ByteBuffer into a native address without a reachability fence. The exact Linux caller now fences
the buffer until alBufferData returns or throws. Cache admission requires this synthetic upload
helper to be present in the actual loader; a missing lifetime guard forces original decoding.
Caller class SHA256: 3032cfe9e6aa8b1e66fa0fe3c7c6794a0b728bd2dfdccdf30d944ce0c259688e.
The guard is an independent catalogued plan (linux-pcm-upload-lifetime-v1), retained even when
the prepared-audio domain is disabled. This does not retain buffers after the call or alter
threads, native arguments, uploads or names.

The prepared RGB row-alignment guard had only applied to Windows. It now also accepts the exact
Linux TextureLoader class 9679ffab9f56e12183bce93dd6a459b6f6d26dfd7ec2230a67476d8cc20c0680
in the already reviewed common archive. It adjusts GL_UNPACK_ALIGNMENT only for the current
thread's exact owned tightly packed prepared RGB buffer, restoring the previous state on both
normal and exceptional exit. Original converter buffers keep original GL state. Both upload
and reload call sites are covered. Windows' 1024 ceiling and serving behavior are unchanged.

## Failures retained

Private evidence root: benchmark-results/2026-09-06-linux-startup (ignored, not shipped assets).

- baseline-discovery failed headless before a desktop existed; excluded from timing.
- baseline-visible, sound off: 17.934 s first usability, verified fully drawn later by screenshot.
  This is not a fully shown-menu timestamp and does not measure audio-cache benefit.
- audio-discovery, sound off: zero audio decodes, no audio performance claim.
- sound-on-candidate and sound-on-control-2 both crashed in Mesa glTexImage2D before menu; the
  second explicitly disabled prepared audio. The original RGB guard excluded Linux.
- aligned-audio-discovery then crashed in libopenal64.so ConvertData from alBufferData on
  pool-1-thread-2. aligned-control (cache off) reached first usability in 23.498 s, with 75
  unpack changes and restorations. The fenced-audio-discovery run reached it in 19.209 s.
- FA1, with cache disabled, also crashed in OpenAL ConvertData. This exposed that the first
  fence installation incorrectly depended on cache readiness. The independent guard corrects
  both original and prepared PCM uploads. Its failure report remains under FA1/hs_err.log.
- The crashed JVM's original launcher exited before the JVM. Private test cleanup was corrected
  to settle the owned process group; the diagnosed survivor was explicitly retired. No failed
  attempt is assigned a menu time or included in successful medians.

These observations support the guards; they do not prove every possible native crash shares
these causes. All failure logs, the control hs_err report, and captured native stacks are retained.

## Verification and timing

Installed tests compare prepared versus original PCM byte-for-byte, channel count, rate, direct
buffer position, null-input errors, missing/corrupt entries, explicit opt-out and custom-stream
behavior. They also prove cache admission declines without the upload fence. Installed ASM tests
verify exact texture upload/reload rewrites, exceptional state restoration, and the PCM upload
fence on both exits. Linux audio fixtures require the installed game's -noverify setting because
its obfuscated classes contain invalid field names. Ordinary full Maven verify runs separately.

First-usability cohort a1affc93: A1/B1/B2/A2/A3/B3, 15-second cooldowns, prepared audio cache disabled/enabled
within otherwise identical sound-enabled Recommended launches. This early cohort had the lifetime
fence only in the cache-on arm; the final cohort below fixes that asymmetry. Cache-off samples 22.655 / 22.582 / 22.615 s,
median 22.615 s; cache-on 18.464 / 19.136 / 18.318 s, median 18.464 s. All six reached the menu.
Each cache-on run served 967 decodes and retained 1083 original decodes, with zero cache failures.
The screenshot at B1 still showed a dimmed Preloading overlay. That frame alone cannot establish
how long the visual transition lasts.

Linux now also records mainMenuOverlayRemovedAt at its original label-removal call. This remains
a separate timestamp; no countdown or loading work is shortened. The installed title show method
starts a 0.5-second fade, while the Preloading label has a separate blink timer. These two events
must not be conflated. On final GB1, an event-driven observer began a screenshot 1.001 seconds
after first usability (capture finished at +1.291 seconds): the menu and Preloading overlay were
still dim. The later screenshot at label removal was clear. First usability was 18.490 seconds;
label removal was 28.170 seconds, a 9.680-second interval. This establishes the observed Linux
transition, not that texture loading itself occupied those additional seconds. It does not
retroactively establish the timing of a Windows screenshot.

Final runtime source: 5deb4d77. JAR SHA256 c8d78d102ae8d24e602ffde5832b3cd3b79fc2e3ebc51026b5f91a84e00d29d7.


Final six-run order: GB1, GA1, GA2, GB2, GB3, GA3; 15-second cooldowns within the automated
remaining five runs. GB1 was the separate screenshot observation immediately preceding those
runs. A disables prepared audio; B enables it. Sound remains enabled in both arms, with the same
independent PCM lifetime and RGB alignment guards. This is a bounded local comparison, not a
randomized campaign or an assurance of universal stability.

| Run | Menu show (s) | Label removal (s) | Interval (s) |
| --- | ---: | ---: | ---: |
| GB1 | 18.490 | 28.170 | 9.680 |
| GA1 | 22.646 | 32.362 | 9.716 |
| GA2 | 22.698 | 32.403 | 9.705 |
| GB2 | 18.326 | 28.030 | 9.704 |
| GB3 | 18.354 | 28.091 | 9.737 |
| GA3 | 22.741 | 32.458 | 9.718 |

Cache-off medians: 22.698 s menu show, 32.403 s label removal.
Cache-on medians: 18.354 s menu show, 28.091 s label removal.
Savings: 4.343 s and 4.312 s respectively. All six reached both markers, with zero contained
adapter failures and zero remaining owned direct texture bytes. Each run recorded 75 unpack
alignment changes and 75 restorations. All three cache-on runs served 967 prepared decodes,
retained 1083 original decodes, and reported zero cache failures. No new native crash occurred
in this cohort; earlier failures remain recorded above.

Full ./mvnw -q verify passed after the independent plan was added to the catalog; the initial
verification caught the missing catalog entry and was corrected before packaging. Installed
Linux audio/fence tests passed. Three-platform CI run
[34003606857](https://github.com/teamleaderleo/preflight/actions/runs/34003606857) passed on
runtime source 5deb4d77. The later evidence-only commit does not change packaged code.

Sound was restored to false after the final run. The measured engine was installed through the
product install command, creating the normal local preflight command and desktop entry. The
prepared audio cache remains available when sound is enabled. The Windows VM remains off and
Linux's desktop remains active; persistent VFIO boot configuration is unchanged.
