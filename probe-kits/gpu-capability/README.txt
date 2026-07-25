STARSECTOR PREFLIGHT — GPU TEXTURE CAPABILITY PROBE KIT

What this kit does
------------------
It asks your graphics driver, directly, which texture formats it will actually accept,
and whether the loader's power-of-two padding is required on your machine.

This matters because every texture-footprint plan depends on the answer, and the answer
is not portable. It varies by GPU, by driver, by operating system, and on macOS by the
OpenGL-over-Metal translation layer. A format that is unremarkable on a 2019 Windows
desktop can be entirely absent on a 2026 Mac. Documentation and vendor marketing are not
a substitute for asking the machine in front of you, so this probe performs real uploads
and reads back what the driver stored: an extension string is a claim, an accepted upload
is a fact, and the two are known to disagree.

The probe is read-only. Starsector is never launched, no file in the installation is
modified, and no transformation, cache read, or cache write is involved. It creates an
offscreen buffer and a few textures, then exits.

Run the probe
-------------
macOS and Linux:

    ./run-gpu-capability-probe.sh [/path/to/starsector]

Windows:

    run-gpu-capability-probe.bat ["C:\Program Files (x86)\Fractal Softworks\Starsector"]

With no argument the usual install locations are tried. A report is written next to the
script as gpu-capability-report-<timestamp>.txt, ending with a one-line JSON summary
suitable for pasting into an issue.

It needs a JDK 17 or newer for `javac`. Everything else comes from your Starsector
installation.

Why it runs on the game's JVM
-----------------------------
The probe uses the game's own lwjgl.jar and its own bundled runtime, so the context it
measures is the one the game gets rather than an approximation.

This is not fussiness. On Apple Silicon the shipped JVM and LWJGL natives are x86_64
only, so the game runs under Rosetta 2 and an arm64 JVM cannot load its native library at
all — the probe fails with UnsatisfiedLinkError if pointed at the wrong runtime. See
docs/evidence/2026-07-25-macos-rosetta-runtime.md.

What the report tells you
-------------------------
  extensions claimed      What the driver says it supports. Recorded, then ignored in
                          favour of the sections below.

  driver-side compression Whether simply asking for a compressed internal format is
                          enough. Where it is, the smallest possible change to the
                          engine's upload is a single different constant, because
                          TextureLoader hardcodes GL_RGBA there.

  offline encoder path    Whether pre-encoded blocks are accepted. This is what allows a
                          good offline encoder to be used instead of whichever fast one
                          the driver ships, and it is the only route that permits a
                          per-texture policy. On real Starsector art the two differ in
                          quality by roughly a factor of two.

  non-power-of-two        Whether the loader's padding of every texture up to a power of
                          two is required by your driver, or is inherited compatibility
                          behaviour that costs video memory for nothing. On a ~70-mod
                          profile that padding is about 1.86 GiB.

  encoder vs driver       Whether the blocks preflight's encoder writes are the blocks
                          your hardware reads. It uploads real encoded data, reads it back
                          decompressed, and compares against preflight's own decoder.

Error codes are OpenGL's own. 0x0000 is success; 0x0500 is GL_INVALID_ENUM, which is what
a driver returns for a format it does not implement.

The encoder-versus-driver section needs preflight-core built, and is skipped with a note
if it is not:

    mvn -q -pl preflight-core -am install -DskipTests

It exists because every fidelity number preflight publishes is produced by decoding the
encoder's output with preflight's own decoder, which on its own is circular — a byte
layout can be wrong in a way both halves agree on, and look perfect until a GPU sees it.
BC1 in particular reads the order of its two stored colours as a mode bit, and an encoder
that ignores it writes blocks that are correct in software and garbage on hardware.

A small disagreement is not a defect. The S3TC specification defines the interpolated
palette entries as weighted averages without pinning the rounding, so drivers may
legitimately differ by about a unit per channel. A layout error looks nothing like that:
it produces wholly different colours, not off-by-one ones, which is why the probe reports
the worst per-channel deviation rather than a pass/fail alone.

What to expect, by platform
---------------------------
Only the macOS result has been measured so far, and it is the constrained one. Results
from Windows and Linux machines are wanted precisely because the answer is expected to
differ:

  macOS (measured)   Apple's OpenGL stops at 4.1. BC1-BC5 work; BC7 and ASTC return
                     GL_INVALID_ENUM, because BPTC is core in GL 4.2 and Apple never
                     shipped the extension. The hardware supports both through Metal;
                     nothing reachable from Starsector's context can use them.

  Windows / Linux    Expected to expose BC7 on any GPU and driver from roughly 2012
                     onward, since BPTC is core in OpenGL 4.2 and vendor drivers there
                     are not capped at 4.1. If that holds, the best available format
                     differs by platform, and any shipped asset pipeline has to select
                     per machine rather than pick one format globally. UNVERIFIED — this
                     is the reasoning, not a measurement, which is why the probe exists.

If you run this on Windows or Linux, the JSON line at the end of the report is the useful
part to share.

Checking a driver you are not sitting at
---------------------------------------
The probe above needs LWJGL, LWJGL needs a window system, and the drivers most worth
checking are on machines nobody is sitting at — a headless rented NVIDIA GPU, or someone
else's Windows box. So the block check also exists in a split form:

    ./run-block-conformance.sh

BlockConformanceVector.java writes a deterministic vector — encoded blocks, plus the
pixels preflight's decoder says those blocks mean — using no GPU at all.
block-conformance-probe.c reads that vector on a GPU using no JVM at all, through CGL on
macOS and EGL's device platform on Linux, neither of which needs a display.

That split has two payoffs. It runs where LWJGL cannot, and it is something a stranger can
run: about 600 KB and one small binary, rather than a JDK, a Maven build and a game
installation.

To check the same vector on rented hardware:

    pip install modal && modal setup
    ./run-block-conformance.sh                 # generates the vector
    modal run modal-block-conformance.py       # --gpu L4 for something newer than a T4

The job is a compile and a few texture uploads, so it costs a fraction of a cent. The
Modal script is UNTESTED — written without NVIDIA hardware to hand — and names its two
likely failure points in comments.

Why bother, when Apple's driver already agrees bit for bit: because the agreement required
matching a quirk nobody could have predicted. On Apple's driver the colour blends truncate
and the alpha blends round, in the same block. The S3TC specification permits both. So the
encoder is verified against exactly one driver, and NVIDIA is the one most Starsector
players actually have. A mismatch would be the more useful result — it would mean the
level tables are Apple-specific and the block cache needs a per-driver decision.

Also here
---------
gl-capability-probe.c is a macOS-only CGL version that predates the portable one. It is
kept because it can be built for a specific architecture, which is how the arm64 and
Rosetta paths were confirmed to see the same driver. The portable Java probe is the one
to run.

Results already recorded:

  docs/evidence/2026-07-25-macos-gl-capability-probe.md
  docs/evidence/2026-07-25-macos-rosetta-runtime.md
  docs/evidence/2026-07-26-encoder-driver-byte-agreement.md
