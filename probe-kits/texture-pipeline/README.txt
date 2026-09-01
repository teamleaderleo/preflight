STARSECTOR PREFLIGHT — TEXTURE LOAD PIPELINE PROBE KIT

What this kit does
------------------
It measures where the time actually goes when the game loads a texture, and prices two
alternatives against it.

Loading a texture is four steps: read the PNG, decode it, walk the decoded raster into an
RGBA buffer padded to a power of two, and upload that to the GPU. Which of those dominates
decides what is worth optimising, and the answer is not obvious — it is easy to assume the
cost is the bus or the disk. On the reviewed machine it is neither: decoding and the raster
walk are about 95% of it, and the upload is under 3%.

The probe also prices two caches against the vanilla path:

  prepared pixels   preflight's existing cache. Skips decode entirely, but stores 4 bytes
                    per pixel on disk.
  block cache       pre-encoded BC blocks. Skips decode too, and is an eighth the size,
                    staying compressed in video memory.

Read-only. The game is never launched and nothing in the installation is modified.

Run the probe
-------------
    ./run-texture-pipeline-probe.sh [/path/to/starsector]

    PREFLIGHT_PROBE_SAMPLES=1000 ./run-texture-pipeline-probe.sh

With no argument the usual install locations are tried. A report is written next to the
script. macOS and Linux; the Windows equivalent does not exist yet.

That timestamped report is local working output. Move any result that must remain reviewable
into docs/evidence before running scripts/prune_local_build_outputs.py; the cleaner bounds old
probe reports along with their compiled probe binaries.

Requirements: a JDK 17 or newer for javac, and preflight-core built:

    mvn -q -pl preflight-core -am install -DskipTests

Why it runs as two processes
----------------------------
On macOS, ImageIO drags in AWT, which initialises CoreFoundation; a GL context created
afterwards segfaults inside it. Deferring the GL work to a later phase of the same JVM is
not enough. So decoding and uploading run as separate JVMs joined by a handoff file.

This costs nothing in fidelity. Upload time depends on how many bytes cross the bus, not on
what those bytes contain, so the upload phase sends correctly sized buffers rather than the
real ones.

Reading the results
-------------------
Two measurement details matter for interpreting the output, both of which produced wrong
answers before they were fixed:

  Stages are timed in separate passes.  Interleaving them makes reads appear about eleven
  times more expensive than they are, because a read measured between a decode and an
  encode is really measuring garbage collection and page-cache eviction.

  Disk is two constants, not one.  StorageProbe measures them separately: a fixed per-file
  cost and a per-byte cost. Which dominates flips between a mod tree of thousands of small
  files and a cache of a few large ones, so a single figure scaled by size is misleading.
  The cache rows are modelled as one sequential pack.

The vanilla row is measured end to end. The two cache rows are modelled — no block cache
exists yet, so this prices one rather than benchmarking it.

On macOS everything is measured under Rosetta, because the shipped JVM and LWJGL natives
are x86_64 only. That is what the game gets, but it is not native speed, and it inflates
the CPU stages specifically. See docs/evidence/2026-07-25-macos-rosetta-runtime.md.

Results already recorded:

  docs/evidence/2026-07-26-texture-load-pipeline-decomposition.md
