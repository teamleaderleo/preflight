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
