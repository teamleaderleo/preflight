# Windows packed-image staging experiment

Objective: reduce remaining main-thread texture work and test repeated native startup toward sub-17.
Base: main 43d86333, installed executable 1e06d416, JAR
949558859f05da4ff10b883c8c4a6d5cc6d19566216c24bdecbc5cd28c7bbbcc.

Diagnostic native cohort 20260906-065204 enabled semantic phases, TEXTURE thread CPU, prepared-load
attribution and upload checkpoints. Interactive menu was 19.322 s, graphics 17.483 s; diagnostic
clocks are not ordinary performance acceptance. Texture calls reported 8.459 s wall / 5.953 s
current-thread CPU excluding the 1 ms cursor call, with 248 coarse-clock skew observations.
The retained periodic upload snapshot contained 14,336 calls / 2.519 s total, maximum 18.269 ms,
none over 50 ms. This is a partial snapshot, not the complete startup GL total. Pack attribution
11.202 s is cumulative across concurrent callers and must not be subtracted from serial wall time.

Candidate moves creation of private packed RGB/ARGB fallback surfaces into the existing prepared
staging producer. The original converter, layout and GL operations remain on main. Both source
pixels and the extra packed array count toward the existing 64 MiB queued-byte ceiling; images
whose combined footprint does not fit stay on the current behavior. No workers or resource
scheduling changes. Only requested Windows prepared-resource paths above the 1024 direct ceiling
and with the packed-converter option enabled can build these surfaces. Consuming transfers the
private surface once; exposing the carrier materializes its mutable raster and drops the private
copy. Installed TextureLoader bytecode again confirms one getData followed by read-only getPixel
loops and unchanged upload/subimage calls.

Phase: full verification, then native measurement. Retain only if justified by repeated launches;
record rejected candidates and restore the previously measured artifact if this fails.


64 MiB candidate executable 1cc0c242116329997460cbd488b5e7fede19a8b0, JAR
6abf2f7f7f1be2b5a16d1840c48296a6326e2f1b34f36fe501f467c1938960ea, passed full installed-fixture
verify in 48.667 s and three-platform CI 33997354767. Ordinary native cohort 20260906-065808
completed menus in 17.306 / 19.443 / 16.424 s (graphics 16.258 / 17.777 / 14.763 s). The final
sample beats the prior 16.690 record, but this set is not consistently sub-17. Staged packed
uses were 42 / 28 / 42; staging misses 210 / 1596 / 109. All runs committed 15,002 resources,
declined 44 at the 1024 ceiling, consumed 102 retained Kaleidoscope resources, balanced 168
alignment changes/restores, and left zero pending/active/in-flight buffers and no resource failures.

Repeat 20260906-070138: menus 17.883 / 19.131 / 19.327 s, graphics 16.164 / 17.443 / 17.666 s.
Staged packed uses 40 / 33 / 32; misses 255 / 1033 / 769. No staging failures. All six samples
are retained, including the slower repeat set. The 64 MiB queue reached its ceiling in every run.

Next composition gives only requested Windows packed-resource staging 128 MiB of combined
source-plus-packed queue capacity. Other platforms and packed/resource opt-outs retain 64 MiB.
This tests whether added packed storage reduced the producer's lead, as slower launches had more
staging misses. No new configuration knob, threads or resource ordering change. The extra
64 MiB is bounded and small relative to this VM's 20 GiB; performance remains unproven.
