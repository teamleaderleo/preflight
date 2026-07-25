"""Runs the S3TC block conformance check on a rented NVIDIA GPU.

Why this exists
---------------
Preflight encodes S3TC blocks offline, and every fidelity number it publishes comes from decoding
those blocks with its own decoder. That is circular unless a real driver agrees. Apple's driver
agrees bit for bit -- but it also turned out to truncate the colour blends while rounding the alpha
blends, in the same block, which is not something the specification pins down or that any amount of
reading would have revealed. So the honest position is that the encoder is verified against exactly
one driver, and NVIDIA is the one most Starsector players actually have.

Renting a GPU for a minute is a cheap way to close that. This is a read-only measurement: it uploads
about 600 KB, reads it back, and prints a diff.

Why it is a C program and not the Java probe
--------------------------------------------
The in-process check needs LWJGL, LWJGL's Pbuffer needs a window system, and a rented GPU is
headless. EGL's device platform does not need one. So the check splits in two: Java writes a
conformance vector on a machine with no GPU (BlockConformanceVector), and block-conformance-probe.c
reads it on a GPU with no Java.

Usage
-----
    pip install modal && modal setup          # one time; this is the part that needs your account

    # Generate the vector locally (needs a JDK and preflight-core built):
    mvn -q -pl preflight-core -am install -DskipTests
    javac -cp preflight-core/target/classes -d /tmp/spfv \\
        probe-kits/gpu-capability/BlockConformanceVector.java
    java -cp preflight-core/target/classes:/tmp/spfv BlockConformanceVector \\
        probe-kits/gpu-capability/block-conformance-vector.bin

    modal run probe-kits/gpu-capability/modal-block-conformance.py
    PREFLIGHT_GPU=L4 modal run probe-kits/gpu-capability/modal-block-conformance.py

The GPU is chosen by environment variable rather than a command-line flag because @app.function
binds it at import time, and Modal's API for overriding it at call time has moved between versions
(with_options does not exist in 1.2.6). An environment variable is read before the decorator runs,
so it works the same on every version.

Cost is a fraction of a cent: the whole job is a compile and a few texture uploads, well under a
minute of the cheapest GPU on offer.

Status: works. Confirmed on a Tesla T4 (NVIDIA 580.95.05, OpenGL 4.6) reporting
preflight-renderer-class: hardware. Getting there took two fixes worth remembering, both recorded in
docs/evidence/2026-07-26-encoder-driver-byte-agreement.md: NVIDIA_DRIVER_CAPABILITIES=all is not
sufficient without the ICD manifest written below, and a harness must check the renderer rather than
the exit status, or it will cheerfully report a software rasteriser as a GPU.
"""

import os
import pathlib
import sys

# Both of these are read by Modal at import time, so they have to be set before it is imported.
#
# The image builder is pinned rather than left to the workspace default because the container's
# contents are part of the measurement: which EGL and GL libraries are present decides which driver
# libglvnd loads, and that is exactly what went wrong on the first hosted run. A probe whose result
# depends on a workspace setting somebody changed last month is not reproducible. Override with
# MODAL_IMAGE_BUILDER_VERSION if a newer builder is needed; valid values in Modal 1.2.6 are
# 2023.12, 2024.04, 2024.10, 2025.06 and PREVIEW.
os.environ.setdefault("MODAL_IMAGE_BUILDER_VERSION", "2025.06")

import modal  # noqa: E402  -- must follow the environment setup above

# Read before the decorator below runs, since that is the only point at which the GPU can be chosen.
GPU = os.environ.get("PREFLIGHT_GPU", "T4")

HERE = pathlib.Path(__file__).parent
PROBE_SOURCE = HERE / "block-conformance-probe.c"
VECTOR = HERE / "block-conformance-vector.bin"

# A CUDA image rather than a plain Ubuntu one, because Modal injects the host's NVIDIA driver
# libraries into containers built from these -- including libEGL_nvidia and its ICD manifest, which
# is what makes a headless GL context possible at all. NVIDIA_DRIVER_CAPABILITIES must include
# "graphics"; the default is "compute,utility", which gives CUDA but no OpenGL, and is the first
# thing to check if the probe reports "No EGL display".
image = (
    modal.Image.from_registry("nvidia/cuda:12.4.1-devel-ubuntu22.04", add_python="3.11")
    .apt_install("libegl1", "libegl-dev", "libgl1", "libglvnd-dev", "libglx-dev", "mesa-utils")
    .env({"NVIDIA_DRIVER_CAPABILITIES": "all", "NVIDIA_VISIBLE_DEVICES": "all"})
    # libglvnd dispatches EGL by reading ICD manifests from this directory. Installing the Ubuntu
    # EGL packages drops Mesa's manifest in; NVIDIA's normally arrives with the driver install,
    # which does not happen here because Modal injects driver libraries into an image that never
    # ran the installer. Without this file the only device libglvnd can see is Mesa's software one,
    # and the probe silently measures llvmpipe on a machine with a perfectly good GPU in it.
    .run_commands(
        "mkdir -p /usr/share/glvnd/egl_vendor.d",
        'printf \'{"file_format_version":"1.0.0","ICD":{"library_path":"libEGL_nvidia.so.0"}}\''
        " > /usr/share/glvnd/egl_vendor.d/10_nvidia.json",
    )
)

app = modal.App("preflight-block-conformance", image=image)


@app.function(gpu=GPU, timeout=300)
def check(probe_source: str, vector: bytes, gpu_label: str) -> int:
    """Compiles the probe against this container's driver and runs it on the vector."""
    import subprocess

    pathlib.Path("/tmp/block-conformance-probe.c").write_text(probe_source)
    pathlib.Path("/tmp/vector.bin").write_bytes(vector)

    print(f"=== rented GPU: {gpu_label}")
    subprocess.run(["nvidia-smi", "--query-gpu=name,driver_version", "--format=csv"], check=False)

    # nvidia-smi succeeding proves CUDA reached the GPU, which is not the same as OpenGL reaching
    # it. The first hosted run had a healthy T4 here and still rendered on llvmpipe, so print the
    # things that actually determine which driver libglvnd will load.
    print("\n=== EGL vendor manifests (libglvnd reads these to find drivers)")
    subprocess.run("ls -l /usr/share/glvnd/egl_vendor.d/ 2>&1", shell=True, check=False)
    print("=== injected NVIDIA GL/EGL libraries")
    subprocess.run(
        "ldconfig -p | grep -Ei 'EGL_nvidia|GLX_nvidia|libnvidia-glcore' | head -10 "
        "|| echo '(none found -- OpenGL will fall back to Mesa software)'",
        shell=True, check=False,
    )
    print()

    build = subprocess.run(
        ["cc", "-O2", "-o", "/tmp/block-conformance-probe", "/tmp/block-conformance-probe.c",
         "-lEGL", "-lGL"],
        capture_output=True, text=True,
    )
    if build.returncode != 0:
        # Second likely failure point: headers present but the EGL/GL dev packages not matching the
        # injected driver. The apt list above is the thing to adjust.
        print("Probe failed to build:")
        print(build.stderr)
        return 2

    result = subprocess.run(
        ["/tmp/block-conformance-probe", "/tmp/vector.bin"], capture_output=True, text=True
    )
    print(result.stdout, end="")
    if result.stderr:
        print("--- stderr ---")
        print(result.stderr, end="")
    return result.returncode


@app.local_entrypoint()
def main():
    if not VECTOR.exists():
        print(f"Missing {VECTOR}.")
        print("Generate it first -- see the usage block at the top of this file.")
        sys.exit(2)
    vector = VECTOR.read_bytes()
    print(f"Sending {len(vector)} bytes of conformance vector to a {GPU}.\n")
    status = check.remote(PROBE_SOURCE.read_text(), vector, GPU)
    # Status 3 means the probe ran to completion on a software rasteriser. Reporting that as a GPU
    # result is precisely the mistake this harness made on its first run, so the outcomes are
    # distinguished here rather than collapsed into "exit code 0 means it worked".
    if status == 0:
        print(f"\n{GPU}'s driver agrees with the encoder. Record the output in docs/evidence/.")
    elif status == 3:
        print("\nThe probe ran on a SOFTWARE rasteriser, not on the rented GPU.")
        print("The comparison is still a real third opinion, but it is not an NVIDIA result and")
        print("must not be recorded as one. Check the two diagnostic sections above: an empty")
        print("egl_vendor.d listing or no libEGL_nvidia means libglvnd never saw the GPU.")
    elif status == 1:
        print("\nMISMATCH. This is the interesting outcome: it means the encoder's level tables")
        print("are Apple-specific and the block cache needs a per-driver decision. Record it.")
    else:
        print("\nThe probe could not run; see the output above. Nothing is proven either way.")
    sys.exit(status)
