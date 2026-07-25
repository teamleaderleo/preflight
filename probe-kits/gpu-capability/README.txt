STARSECTOR PREFLIGHT — GPU TEXTURE CAPABILITY PROBE KIT

What this kit does
------------------
It asks your graphics driver, directly, which texture formats it will actually accept,
and whether the loader's power-of-two padding is required on your machine.

This matters because every texture-footprint plan depends on the answer, and the answer
is not portable. It varies by GPU, by driver, by operating system, and on macOS by the
OpenGL-over-Metal translation layer. A format that is standard on a 2019 Windows desktop
can be entirely absent on a 2026 Mac, and the reverse is also true. Documentation and
vendor marketing are not a substitute for asking the machine in front of you.

The probe is read-only. Starsector is never launched, no file is modified, and no
transformation, cache read, or cache write is involved. It creates a few offscreen
textures, reads back what the driver stored, and exits.

Run the probe
-------------
1. Open this folder.
2. Double-click:

     run-gpu-capability-probe-macos.command

3. A report is written next to the script as gpu-capability-report-<timestamp>.txt.

It needs the Xcode Command Line Tools for `clang`. If they are missing the script says
so and gives the one command that installs them.

What the report tells you
-------------------------
  Legacy profile          The context Starsector actually gets. LWJGL 2 creates a
                          compatibility-profile context, so these are the numbers that
                          govern the game. The core profiles are reported only to
                          distinguish "the driver cannot do this" from "the old profile
                          does not expose it".

  driver-side compression Whether simply asking for a compressed internal format is
                          enough. Where it is, the smallest possible change to the
                          engine's upload is a single different constant.

  offline encoder path    Whether pre-encoded blocks are accepted. This is what allows a
                          good offline encoder to be used instead of whichever fast one
                          the driver ships. The two differ in quality by roughly a factor
                          of two on real Starsector art.

  non-power-of-two        Whether the loader's padding of every texture up to a power of
                          two is required by your driver, or is inherited compatibility
                          behaviour that costs video memory for nothing. On a ~70-mod
                          profile that padding is about 1.86 GiB.

Error codes in the report are OpenGL's own. 0x0000 is success; 0x0500 is
GL_INVALID_ENUM, which is what a driver returns for a format it does not implement.

Scope
-----
macOS only. It uses CGL to create an offscreen context, which is the least fragile way to
get a real driver on this platform without opening a window. The questions it asks are
not macOS-specific, so a Windows or Linux equivalent would be worth having; it does not
exist yet.

Results already recorded from this probe are in:

  docs/evidence/2026-07-25-macos-gl-capability-probe.md
