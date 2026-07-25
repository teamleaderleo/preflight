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
    modal run probe-kits/gpu-capability/modal-block-conformance.py --gpu L4

Cost is a fraction of a cent: the whole job is a compile and a few texture uploads, well under a
minute of the cheapest GPU on offer.

Status: UNTESTED. Written on a machine with no NVIDIA hardware and no Modal account, so the code
below is reasoned from the documented behaviour of EGL_EXT_platform_device rather than observed. The
two likely failure points are called out in comments where they occur.
"""

import pathlib
import sys

import modal

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
)

app = modal.App("preflight-block-conformance", image=image)


@app.function(gpu="T4", timeout=300)
def check(probe_source: str, vector: bytes, gpu_label: str) -> int:
    """Compiles the probe against this container's driver and runs it on the vector."""
    import subprocess

    pathlib.Path("/tmp/block-conformance-probe.c").write_text(probe_source)
    pathlib.Path("/tmp/vector.bin").write_bytes(vector)

    print(f"=== rented GPU: {gpu_label}")
    subprocess.run(["nvidia-smi", "--query-gpu=name,driver_version", "--format=csv"], check=False)
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
def main(gpu: str = "T4"):
    if not VECTOR.exists():
        print(f"Missing {VECTOR}.")
        print("Generate it first -- see the usage block at the top of this file.")
        sys.exit(2)
    vector = VECTOR.read_bytes()
    print(f"Sending {len(vector)} bytes of conformance vector to a {gpu}.\n")
    # The decorator's gpu= is fixed at import, so overriding it needs with_options; passing --gpu
    # without this would silently keep running on a T4 and quietly make the flag a lie.
    status = check.with_options(gpu=gpu).remote(PROBE_SOURCE.read_text(), vector, gpu)
    if status == 0:
        print("\nNVIDIA agrees with the encoder. Record the output in docs/evidence/.")
    elif status == 1:
        print("\nMISMATCH. This is the interesting outcome: it means the encoder's level tables")
        print("are Apple-specific and the block cache needs a per-driver decision. Record it.")
    else:
        print("\nThe probe could not run; see the output above. Nothing is proven either way.")
    sys.exit(status)
