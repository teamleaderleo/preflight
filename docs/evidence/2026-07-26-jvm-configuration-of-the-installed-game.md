# What the installed launcher actually asks the JVM for (2026-07-26)

Prompted by two operator questions: whether the heap setting in `vmparams` affects the texture
memory this project has been measuring, and why Starsector has not moved past Java 17.

Everything below is measured on the reviewed installation — `/Applications/Starsector.app`, Zulu
17.0.10 x86_64, Apple M5 — by running the bundled JVM with `-version`. No game was launched.

## The heap setting is not the VRAM setting

The launcher sets `-Xms6144m -Xmx6144m`. That is the **Java heap**. The 6.91 GB working set this
project reports is driver-side texture memory allocated through `glTexImage2D`, which lives outside
the heap entirely and is not bounded by `-Xmx`. Lowering the heap does not reduce it by a byte, and
the 1.86 GB of power-of-two padding is likewise untouched by any `vmparams` change.

**But the change was not inert**, for an unrelated reason. The launcher also sets
`-XX:+AlwaysPreTouch` with `-Xms` equal to `-Xmx`, which makes the JVM write to every page of the
heap before `main()` runs. Measured, three runs each:

| `-Xms` | startup with `AlwaysPreTouch` |
|---|---|
| 4096m | 774 ms |
| 6144m | 949 ms |
| 8192m | 1241 ms |

Against `-XX:-AlwaysPreTouch` at 6144m: **70 ms**. So pre-touch alone costs roughly **950 ms**, and
scales at about **150 ms per GB** of heap.

An 8192m → 6144m change therefore bought about **290 ms of startup** and **no VRAM at all** — the
opposite of the intended effect, in a helpful direction. This is a launch cost that exists before any
resource preparation begins, and it is not something preflight can cache away.

The flag is defensible on its merits: pre-touching trades startup for the absence of page-fault
stalls later. Whether that trade is right for a game that already loads for a long time is a
different question, and it is measurable rather than arguable.

## Much of the tuned flag list is inert on Apple Silicon

The bundled JVM is `Mach-O 64-bit executable x86_64` on an arm64 host, so it runs under Rosetta 2
(consistent with [2026-07-25-macos-rosetta-runtime.md](2026-07-25-macos-rosetta-runtime.md)). Rosetta
does not provide AVX. The VM resolves the launcher's requests accordingly:

| requested | effective | note |
|---|---|---|
| `-XX:UseAVX=3` | **0** | warns `UseAVX=3 is not supported on this CPU` |
| `-XX:+UseBMI1Instructions` | **false** | warns; AVX is a prerequisite |
| `-XX:+UseBMI2Instructions` | **false** | warns |
| `-XX:+UseFMA` | **false** | |
| `-XX:+UseCLMUL` | true | SSE-level, available |
| `-XX:UseSSE=4` | 4 | |

Three warnings are printed on every launch. `-XX:AVX3Threshold=0` and `-XX:TrimNativeHeapInterval`
are also unsupported here.

This matters more than it looks, because of `compiler_directives.txt`: the package `com/fs/graphics/*`
— which contains `TextureLoader` — is configured **C1-excluded, C2-enabled with `Vectorize: true`**.
The game is explicitly asking for vectorised C2 compilation of the texture path, and on this machine
the widest vector unit the JVM will use is SSE (128-bit), because AVX is off. It is also C2-only, so
that code has no C1 tier to warm up through.

## Java 17 is not held there by the version

The interesting result is a negative one.

Running the launcher's 67 `-XX` flags against **Oracle JDK 21 (arm64)** rejects 18 of them fatally.
That looks like a version verdict until the same sweep is run against **Oracle JDK 17 (arm64)**,
which rejects **exactly the same 18**. Version is not the variable. The two variables are:

- **Architecture.** `UseFastStosb`, `UseAVX`, `AVX3Threshold`, `UseSSE`, `UseSSE42Intrinsics`,
  `UseBMI1Instructions`, `UseBMI2Instructions`, `UseCLMUL`, `UseUnalignedLoadStores`,
  `UseXMMForObjInit`, `UseXmmI2D`, `UseXmmI2F` do not exist on aarch64 builds. A native ARM JDK
  refuses to start on this launcher regardless of version.
- **Vendor.** `UseShenandoahGC` and its four `Shenandoah*` companions are absent from Oracle builds.
  Shenandoah ships in OpenJDK/Temurin/Zulu, not Oracle JDK. This is a build-configuration difference,
  not a deprecation.

The bundled Zulu 17 x86_64 accepts all 67 apart from the two unsupported-on-this-platform ones above.

**Also ruled out: preview classfiles.** `--enable-preview` in the launcher suggests version-locked
code, since a preview classfile (`minor_version == 65535`) is rejected by any JDK other than the one
that produced it. Scanned every class in the shipped jars:

| jar | classes | classfile version | preview-flagged |
|---|---|---|---|
| `starfarer_obf.jar` | 2818 | major 61 (Java 17) | **0** |
| `starfarer.api.jar` | 3450 | major 61 | **0** |
| `fs.common_obf.jar` | 90 | major 61 | **0** |
| `fs.sound_obf.jar` | 24 | major 61 | **0** |
| `janino.jar` | 357 | major 50/49 (Java 6/5) | 0 |
| `lwjgl.jar` | 629 | major 49/50 (Java 5/6) | 0 |

Not one preview classfile. Java 17 bytecode runs unmodified on 21 and later.

### So what would actually break

Nothing measured here reproduces a version effect, so the following are candidates rather than
findings — but they are where the risk concentrates, and all of them are about the age of the
surrounding stack rather than the game's own code:

- **`lwjgl.jar` is LWJGL 2**, compiled for Java 5. `org/lwjgl/MemoryUtilSun$AccessorUnsafe` uses
  `sun.misc.Unsafe`, whose memory-access methods are deprecated for removal and progressively
  restricted from JDK 23 onward.
- **`xstream-1.4.10.jar` is the savegame serializer**, and
  `SunUnsafeReflectionProvider` / `SunLimitedUnsafeReflectionProvider` reach through `Unsafe` to
  instantiate objects without constructors. Serialization libraries breaking on new JDKs is the most
  common form this failure takes, and it would present as corruption or failure on load — which is
  what "instability" usually means in practice.
- **Deep `--add-opens` into `java.base` and `java.desktop`**, including two malformed ones
  (`java.base/java.nio.Buffer.UNSAFE` and `java.desktop/java.awt.Rectangle` name classes, not
  packages). Integrity-by-default work from JDK 21 onward keeps narrowing this.
- **`-noverify`**, deprecated in JDK 13, still accepted with a warning in 21. Preflight's own
  bytecode work depends on verification being off here, so its eventual removal is worth tracking.

## What was not tested

The one configuration that would isolate version cleanly — a **Zulu or Temurin 21 x86_64** build,
which has both the x86 flags and Shenandoah — is not installed on this machine. Until that runs, the
claim here is bounded: *the launcher's flag list fails for reasons of architecture and vendor, and
those reasons are present in 17 as much as in 21.* It is not a claim that JDK 21 is safe for this
game.

The `AlwaysPreTouch` timings are also measured under Rosetta, so the absolute milliseconds are
specific to this machine; the ~150 ms/GB slope is the transferable part.
