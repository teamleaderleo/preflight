# Windows prepared-resource bytecode contracts

Date: 2026-09-05. Inspection baseline:
`6f2b3132f44d3153bd57b9b2de81c9b7b0aacd2d`.

This records installed-bytecode inspection, local JVM fixtures, and the Windows
prototype observations below. No performance improvement or default promotion is
claimed. The baseline SHA identifies the starting checkout; each operator observation
binds its own source and packaged bytes.

## Exact input identities

JAR entries were read in memory, without extracting case-sensitive obfuscated
names onto disk. SHA-256 below covers complete archive or uncompressed class bytes,
not a disassembly or transformed class.

| Input | SHA-256 |
| --- | --- |
| `/home/leo/Windows-Share/Diagnostics/windows-texture-upload-bytecode/fs.common_obf.jar` | `5a26d047baefc6dcd763121a17d170e3b864bfb19a83d11f645ba8be49f1641b` |
| `/home/leo/Windows-Share/Diagnostics/windows-starfarer-obf.jar` | `5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8` |

Classes in the common archive:

| Class entry | SHA-256 |
| --- | --- |
| `com/fs/graphics/TextureLoader.class` | `7d89b44c9401a122529450d17407dbfc8d52e13a9f7eb941dc93125eb5fc153b` |
| `com/fs/graphics/Object.class` | `b4666849768f27009a32119698d21cf7ef8c78e5bd22b6a8c6520e81708b2162` |
| `com/fs/graphics/oOoO.class` | `af75b95d99dcc403ee6487c6f3d8c89e09dcc6bc26214318fa37c3873a513645` |
| `com/fs/graphics/L.class` | `9e339c5a0edadebdd81b088e0882f5a00b4696b9f5e862a9beec3ff03c439f3e` |
| `com/fs/graphics/L$1.class` | `ac01b004ecbb323ee81cc2cd969b30fe9803db6b8c2622de4b87800e11ad465f` |

Classes in the Starfarer archive:

| Class entry | SHA-256 |
| --- | --- |
| `com/fs/starfarer/loading/ResourceLoaderState.class` | `91234c03bb3938180f5a4a0c552eaf2af46df57774530890441355c18e86b6de` |
| `com/fs/starfarer/loading/ResourceLoaderState$Oo.class` | `e0df2969d52e0bbc4eae7c3a0c59d4d7a3b498a3f01f859c902a9f3ff00d49b6` |
| `com/fs/starfarer/loading/ResourceLoaderState$o.class` | `e34c8c1974f9139bf142e05453491f78366143e5ad0f94d12454e08d0a07f08f` |
| `com/fs/starfarer/util/ReplaceableSprite.class` | `cb075b5c605fd3b6ea1aa69757e01650e86dc14dc6f5ad79105c8ad404be67ab` |

The last class is an inspected upstream replacement caller, not an additional
dependency of repository destruction. Resource enum and entry hashes accompany
the enclosing loading-state hash; neither enum ordinal alone nor a path resembling
a texture establishes an admitted TEXTURE obligation.

## Texture handle and destruction

The concrete texture handle (called TextureHandler in this investigation) is
`com/fs/graphics/Object`. It does not implement a destructor/finalizer or contain
a GL deletion call. Neither cache removal nor global repository clearing deletes
the GL texture. This is a negative finding about these exact classes, not a claim
that no other game subsystem ever calls `glDeleteTextures`.

| Handle member | Exact descriptor and behavior |
| --- | --- |
| `<init>` | `(IILjava/lang/String;)V`: target, GL ID, resource path; colors start white, deferred flag false |
| `ö00000` | `()I`: GL ID |
| `Ø00000` | `()V`: calls `GL11.glBindTexture(II)V` with stored target and ID |
| `Õ00000` / `o00000` | `()Ljava/lang/String;` / `(Ljava/lang/String;)V`: get/set resource path |
| `oO0000` / `Ò00000` | `()Ljava/lang/String;` / `(Ljava/lang/String;)V`: get/set logical registration ID |
| `ô00000` / `o00000` | `()Z` / `(Z)V`: get/set deferred flag |
| `Ò00000` / `o00000` | `(I)V`: set source width / height; recompute ratios |
| `Object` / `Ô00000` | `(I)V`: set backing width / height; recompute ratios |
| `Object` / `Ô00000` | `()I`: source width / height |
| `o00000` / `ÒO0000` | `()F`: source height/backing height / source width/backing width |
| `Object`, `o00000`, `Ò00000` | `(Ljava/awt/Color;)V`: three distinct color setters |

The resource plan captures the **incoming** handle before stock code can replace
local 1. Runtime consumption declines whenever that captured reference is non-null.
It does not manufacture handles, overwrite IDs, delete textures, or change bind calls.

## Per-loader path cache, replacement, reload

`TextureLoader.ÔO0000:Ljava/util/HashMap;` is per instance, unsynchronized, and keyed
by the exact supplied string. `o00000()Ljava/util/HashMap;` returns the live map.
There is no canonicalization, reference counting, generation check, or stale-entry
check in its getter.

| Method on `TextureLoader` | Contract |
| --- | --- |
| `o00000(Ljava/lang/String;)Lcom/fs/graphics/Object;` | Cache hit returns identical handle immediately. Normal miss calls seven-argument upload with null handle and `3553,6408,9729,9729,false`, then inserts by path. Deferred miss creates `(3553,-1,path)`, marks deferred, caches it. |
| `new(Ljava/lang/String;Ljava/awt/image/BufferedImage;)Lcom/fs/graphics/Object;` | Uses the same cache; a hit ignores the supplied image. |
| `o00000(Ljava/lang/String;Ljava/lang/String;)V` | Arguments are ID, path. Gets path handle, changes its logical ID, then calls global insert-only registration. |
| `Ó00000(Ljava/lang/String;)V` | Removes that exact path key only; no GL operation. |
| `o00000(Lcom/fs/graphics/Object;Ljava/lang/String;)V` | Normal replacement calls upload with existing handle and final boolean true; inserts incoming handle under the new path without removing old path aliases. Deferred mode marks the handle and updates its path, then returns without cache insertion. |
| `o00000(Lcom/fs/graphics/Object;)V` | Deferred mode marks and returns. Otherwise reads stored resource path, throws `RuntimeException` if null, and delegates to replacement. |

Stock quirk retained: the seven-argument upload allocates a new local handle when
the incoming handle is null **or its ID is -1**. Replacement's void caller discards
the returned handle and caches its original argument. The prototype must not
silently repair this behavior or admit that replacement as a fresh upload.

`ReplaceableSprite.update()V` invokes the existing-handle replacement method,
updates sprite size from the handle, and notifies its delegate. Its
`replaceTexture(Ljava/lang/String;)V` only stages the requested filename. These
paths are excluded by incoming-handle admission, even if a matching sidecar exists.

## Global repository

`com/fs/graphics/oOoO.new:Ljava/util/Map;` is a static `HashMap`, independent of the
loader path cache. Its loader is `o00000:Lcom/fs/graphics/TextureLoader;`.

| Repository method | Contract |
| --- | --- |
| `Ò00000(Ljava/lang/String;)Lcom/fs/graphics/Object;` | ID lookup; returns null when absent. |
| `super(Ljava/lang/String;Lcom/fs/graphics/Object;)V` | Insert only if `containsKey(id)` is false. Does not replace an existing ID. |
| `super(Ljava/lang/String;Ljava/lang/String;)V` | Delegates ID/path registration to the shared loader. |
| `super()Ljava/util/Map;` | Exposes live global map. |
| `String()Lcom/fs/graphics/TextureLoader;` | Exposes shared loader. |
| `Ó00000(Ljava/lang/String;)V` | Removes the same supplied string from loader path cache and global ID map; aliases may survive. |
| `Ò00000()V` | Clears loader cache then global map, without GL deletion. |
| `super(Ljava/lang/String;)V` | Iterates global **ID keys** with `startsWith(prefix)` and reloads their handles. |
| `Ó00000()V` | Reloads flagged handles, then clears each flag. |
| `Õ00000()Z` / `super(Z)V` | Read/write global deferred flag. |

No further game-class hash is needed for the removal/clear implementation:
it calls the already-pinned loader and JDK maps. Reload uses the already-pinned
loader and handle. GL deletion helpers elsewhere are not invoked by this repository.

Aliasing matters: registering another ID for a cached path mutates the shared
handle's logical ID; registering an already-present ID for another path can load
and cache a new handle while the global repository retains the old handle. The
plan leaves these original statements and their order unchanged.

## Main obligation and completion seams

In `ResourceLoaderState.init(Ljava/util/Map;)V`, original bytecode offset (BCI)
2185 is the queued TEXTURE arm's call to
`oOoO.super(Ljava/lang/String;Ljava/lang/String;)V`. Both arguments are the entry's
`o00000:Ljava/lang/String;` path. The plan scopes only this arm, preserving the
earlier queue/dedup decisions and the later weight/progress accounting. Other
startup texture calls at BCI 18, 25, 32, and 39 are not this obligation boundary.

The upload method is `TextureLoader.o00000` with descriptor
`(Lcom/fs/graphics/Object;Ljava/lang/String;IIIIZ)Lcom/fs/graphics/Object;`.
Offsets refer to original bytes, not transformed instruction indices:

| BCI | Original operation and prototype preservation |
| --- | --- |
| 3–32 | Reuse handle, or generate ID and construct handle. Captured entry argument remains the fresh/replacement gate. |
| 34–39 | Set resource path and bind handle; remains on calling main thread. |
| 52 | `Ô00000(Ljava/lang/String;)Ljava/awt/image/BufferedImage;`: replace invocation with typed take/image or unchanged decoder fallback. |
| 57–135 | Loader source dimensions, optional image transform, handle source dimensions, RGB/RGBA choice: unchanged. Non-null transform excludes sidecar consumption. |
| 141 | `o00000(Ljava/awt/image/BufferedImage;Lcom/fs/graphics/Object;)Ljava/nio/ByteBuffer;`: typed `Completion.prepare()` success replays the wrapper's backing-dimension setters unconditionally, its ordered color-field writes, and its buffer; decline directly invokes `preflight$original$convertPixels` on `Completion.image()`. Without completion, existing converter wrapper remains. |
| 146–167 | Loader fields `õ00000`, `interface`, `Ó00000` transfer to handle setters `Object`, `o00000`, `Ò00000`, respectively: unchanged. |
| 170–275 | Mipmap decision and GL texture parameters: unchanged. |
| 278–344 | Original subimage/image selection and GL upload arguments: unchanged. |
| 347–417 | Original byte accounting and buffer cleanup: unchanged; prepared exception-release handler still covers the new calls. |

The final boolean is **subimage selection**, not a reliable reload identity flag.
For original dimensions both <=1024, or a path in `TextureLoader.null:Ljava/util/Set;`,
stock enables mipmaps and forces the boolean false. Larger non-forced textures
retain the caller's boolean, allowing reload through `glTexSubImage2D`.

Composition contract: pass the unmodified loader `ClassSignature` and bytes after
prepared-pixel plus fold rewrites to `TexturePreparedResourceLoaderPlan.transform`.
It internally checks `TexturePreparedResourceRuntime.requested()` and declines when
the property/worker configuration is off, as well as wrong original hash, missing prerequisite methods, duplicate installation,
ambiguous decode/converter sites, missing release coverage, or unrecognized replay.
A non-null result is the installation signal; the plan sets no runtime latch.

Runtime API: `take(String,Object,Object):Completion` receives raw path, transform,
and captured incoming handle. `Completion.image():BufferedImage`,
`prepare():TexturePreparedPixelRuntime.PreparedPixel`, and
`creditOriginalFallback():void` are public linkage requirements. Coherent fallback
credits the existing shared-hit guard once; ORIGINAL_IMAGE does not earn a prepared
hit. Direct decline must use original conversion, not retry the carrier wrapper.

## Stock publication and lifetime

`L.Õ00000:Ljava/util/List;` is a synchronized image queue;
`L.void:Ljava/util/Map;` is a `ConcurrentHashMap` of path to image;
`L.String:Ljava/awt/image/BufferedImage;` is the sentinel.
`L.Õ00000(Ljava/lang/String;)Ljava/awt/image/BufferedImage;` checks queue/map membership,
gets an image, rejects null/sentinel, removes its key, and returns. It polls at
10 ms and consumes interruption by returning null. Its get/remove pair is not atomic.

The sidecar is published beside the existing worker decode result. Main consumption
must wait for that exact object to appear in the stock map and use conditional
`remove(path, exactImage)` once. It must not claim on sidecar publication alone.
The original getter hook retires sidecars consumed by other paths. Native scheduling
and GL ownership remain stock/main; admission pins the actual worker/main identities.

Typed ownership is claimed once: conditional atomic removal of the exact stock image,
actual-main scope, and once-only preparation prevent a second typed commit. The
prototype does not strengthen the stock getter's separate get/remove semantics for
unknown consumers that may already hold an image reference. Such consumers retain
their original behavior; the getter hook retires a matching sidecar only once.
During review, a stale sidecar paired with a
different ordinary stock image could previously poll indefinitely; the concurrently
updated runtime now retires that sidecar and returns to the original getter when it
sees a non-sentinel ordinary result. Runtime ownership also now checks active session,
main thread, current scope, once-only preparation, and revocation/retirement at end.
Those runtime changes belong to the runtime owner, not the loader-plan patch.

## Validation and current limits

The original three focused structural checks passed against these exact Windows
bytes. They cover prerequisite/hash gates, duplicate refusal, ASM stack analysis,
other-method equivalence, GL call sequence, and exception coverage.

Executable tests in `TexturePreparedResourceLoaderPlanTest` use actual game loader,
handle, repository, and preloader bodies with fake LWJGL/logging endpoints and the
real agent completion runtime. Reflection seeds admission; this does not test full
startup admission, archive lookup provenance, or worker scheduling. The tests are
opt-in through `preflight.starsector.common.jar`; no game assets enter the repository.

Initial executable result exposed a **VerifyError** in the composed loader's
`new(Ljava/lang/String;)Ljava/nio/ByteBuffer;`: its merged stream local became
`java/lang/Object` before a constructor requiring `java/io/InputStream`.
The prepared+fold baseline failed too, before the new completion branch executed.
The blanket common-superclass fallback in `SafeClassWriter` caused this: the merge
of `BufferedInputStream` and `InputStream` must retain `InputStream`. The adjacent
fix adds explicit `SafeClassWriter(int flags, boolean bootstrapHierarchy)` opt-in.
Only `TexturePreparedResourceLoaderPlan` and `TexturePreparedResourcePlan.write`
select true. It resolves only pairs of `java/` names with `Class.forName(name, false, null)`;
it never consults an application/game/context classloader. Unknown/non-bootstrap
types retain the conservative Object fallback, which is explicitly **not** claimed
to guarantee verification in every context. `SafeClassWriterTest` checks both
stream merge directions, shared ancestors/interfaces, unknown types, and a context
classloader that records any attempted use. Both existing constructors and explicit
false retain the historical merge behavior, covered by a dedicated regression test.

The first global version of the fix changed an unrelated pinned GraphicsLib generated
replacement hash during full-reactor verification. The opt-in restriction restores
`GraphicsLibLazyNormalPlan.OPTIMIZED_SHA256` to
`12570bba67842564652d01f31fed550f62f500e91eac29145ac0fae189fa4c6a`
without updating any expected hash or registry match. Its existing exact-hash test
passes again. No other writer opts into this prototype frame change.

The legacy prepared+fold intermediate therefore retains its historical frames and
is not claimed to link by itself in this JVM fixture. The new final prototype compose
repairs frames across its output class; that emitted class links and executes without
test-only frame repair. The executable reference now uses the **unmodified installed
loader**, not the intermediate prepared+fold rewrite. Other-method structural
comparison excludes stack-map entries that the final compose intentionally repairs;
it continues comparing instructions, exception handlers, other metadata, and maxima.
No original game method body is stripped or replaced to make the executable test pass.

Executable testing also exposed a direct-completion metadata defect: copying the
existing wrapper's optional dimension gates left the new handle's backing sizes at
zero and its texture-coordinate ratios at 0.0 when those gates were off. The
resource-loader branch now always replays both backing setters after successful
typed preparation. `PreparedPixel.width/height` describe the actual supplied buffer;
this is necessary for padded, power-of-two, and unpadded success alike. The original
GL dimension-fold and upload body remain unchanged. Existing non-completion wrapper
behavior is outside this scoped fix.

Latest focused result after opt-in restriction: **45 tests, 0 failures, 0 errors,
0 skipped** (Maven verify, local host JVM; three build warnings). Nine loader-plan
tests, five frame-writer tests, and 31 GraphicsLib tests passed. Checks include:

- Actual linkage of public Completion methods, including shared-hit fallback credit.
- Direct upload bytes, GL arguments/order, source dimensions, all three colors, and
  handle texture-coordinate ratios compared with original conversion, gates off.
- Direct-memory ceiling decline through original conversion on a readable carrier;
  shared-hit guard credited and metadata/pixels preserved.
- A 1025x3 NPOT carrier declining direct preparation, using the original converter
  with intentionally incorrect stored colors to prove colors are recomputed; padded
  pixels and metadata match baseline. Reload uses original `glTexSubImage2D` path.
- Actual handle constructor, path-cache identity hits, logical-ID aliases, insert-only
  global registration, replacement/reload identity, and map-only removal/clear.
- Simulated GL upload failure: exception propagates, no handle is published into
  either map, prepared direct allocation is released, in-flight accounting retires.
- Explicit property-off refusal and structural/hash/composition checks.

The fake endpoint captures buffers before cleanup; it does not emulate a driver.
The fixture manually seeds admission and binds its producer identity to the test
thread; it is not evidence for concurrent worker scheduling or complete registry
admission. Full-reactor verification and Windows execution remain separate gates.

Reproduce the focused gate from repository root:

```sh
./mvnw -pl preflight-agent -am \
  '-Dtest=TexturePreparedResourceLoaderPlanTest,SafeClassWriterTest,GraphicsLib*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dpreflight.starsector.common.jar=/home/leo/Windows-Share/Diagnostics/windows-texture-upload-bytecode/fs.common_obf.jar \
  verify
```

Implementation owners: [loader plan](../../preflight-agent/src/main/java/dev/starsector/preflight/agent/TexturePreparedResourceLoaderPlan.java),
[batch/prefetch plan](../../preflight-agent/src/main/java/dev/starsector/preflight/agent/TexturePreparedResourcePlan.java),
[resource runtime](../../preflight-agent/src/main/java/dev/starsector/preflight/agent/TexturePreparedResourceRuntime.java),
[executable/structural tests](../../preflight-agent/src/test/java/dev/starsector/preflight/agent/TexturePreparedResourceLoaderPlanTest.java).

## First operator observation and corrective gates

The first Windows run used source `6f1c3f8c5d15a530d5bfeb57db3b27b0bee67c8d`
and JAR `faebee0424cf29c87c2c6c8257073f1127415b628dcfe6ee071b80c3d6f7fc47`.
It reached the interactive title with clean adapter health, but is **rejected**:
admission was zero with one decline, and shutdown was not graceful. The archive is
`/home/leo/Windows-Share/Diagnostics/20260905-prepared-resource-first-failed.zip`.
Archive SHA-256: `94a3a36b5d0f79c69cbc6180839a5f23322dc0dab7c98297514f1618e24ef028`.
Its timing is not evidence for the prepared-resource path because that path never admitted work.

The native prefetch count included 35,877 duplicate declines alongside 15,003 unique
prepared enqueues. Admission must bound distinct obligations rather than reject the
duplicated native list. The corrected bounds are 262,144 scanned native records and
32,768 distinct prepared obligations; neither list order nor stock deduplication changes.
Telemetry now distinguishes admission reasons and reports the actual record count.

The same observation recorded one pack read failure/disable, a worker map-put exception,
and only four completed retained Kaleidoscope results out of 102 seeded paths. Interruption
during a shared FileChannel read is the suspected cause. Fixed reason labels now distinguish
interrupted reads, closed channels, and other I/O errors. The prototype waits at most five
seconds for the unchanged worker queue and its in-flight result to finish before stock
interrupt/retention cleanup; it adds no worker and changes no queue order. Timeout and
wait duration are reported. Exceptional batch exit still revokes immediately.

The typed direct branch also enforces its 1024-pixel ceiling independently of the existing
coherent-direct option. Larger prepared images go through the original converter and
unchanged GL policy, including recomputed colors and original padded layout. This matters
on Windows llvmpipe, where Recommended enables padded coherent-direct serving by default.

Cross-platform validation exposed two inherited test issues: action receipts were published
with a visible partial-file window, and oversized-file tests timed hundreds of MiB of
legitimate bounded reads. Test fixtures now publish receipts atomically and exercise the
existing small read-limit overload against the same large sparse files. Production reader
bounds and timeouts are unchanged. The desktop source lock was also stale at the starting
SHA: review of `915a4ba5..6f2b3132` found only the already-accepted Windows upload-policy and
Kaleidoscope selection/diagnostic changes in `RunCommand`; no new destinations, executables,
network access, or shell capability. Only its reviewed digest was refreshed.
