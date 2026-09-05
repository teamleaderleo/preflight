# Windows prepared byte-barrier experiment

Owner: current Codex task, authorized by Leo to pursue the five proposed performance avenues.
Starting main: `5fe43a80e0715a2957398e787c7c6e7ea3087861`.
Phase: local implementation verified; Windows diagnostic next. No speed claim yet.

## Contracts and implementation

[Parent contracts](2026-09-05-windows-prepared-resource-contracts.md) own installed hashes,
handle/cache/reload semantics, GL policy, and the 1024 ceiling. [Earlier claims](2026-09-05-windows-prepared-resource-claims.md)
own rejected early claims and the no-win wakeup experiment.

Re-inspected installed common `L$1.run`: raw-byte loop tests `õ00000.isEmpty()` at BCI 101–109;
fallthrough at 112 is the sole normal image-phase entry. Images are first dequeued at 127,
with the loop test at 213–221. The hook runs at fallthrough, outside both queue monitors.
It removes admitted prepared image jobs before image ownership begins. It does not alter byte
jobs, add workers, move GL, or depend on a fabricated sentinel. If the hook is absent, it removes
nothing. The resource list is admitted immediately before worker start (core BCI 1857), after
stock enqueues (1829/1844); this prototype skips image-worker admission, not the enqueue itself.

Exact prepared identities, stock result conflicts, worker identity, session lifetime, typed
main scopes and the independent property all gate consumption. Existing transform and handler
cases still decline typed take. Prepared read failure retires the removed job and permits original
decode fallback. Remaining unknown and late jobs preserve their order. Counters distinguish
removed jobs, taken identities and discarded unused identities. No default promotion.

The optional upload checkpoint records the last attempted native call and buffer bounds in one
bounded overwritten file before GL entry. No GL query or additional worker is used. Its I/O is
intrusive and requires separate runs from startup comparisons. An adjacent probe defect was fixed:
subimage dimensions occupy different argument slots from image dimensions; timing telemetry now
uses actual width and height rather than y-offset and width. Native calls are unchanged.

## Remaining authorized work

1. Run barrier diagnostic on native Windows, capture/settle any stall, compare same package with
   explicit barrier false without intrusive probes if healthy.
2. Investigate bounded upload-buffer reuse/read-directly options. Installed TextureLoader cleanup
   explicitly attempts reflective cleaner invocation: recycling a buffer after existing cleanup
   risks reuse of freed memory. Any pool must own allocation and intercept cleanup for its exact
   buffers; ordinary buffers retain original cleanup. Do not implement naive post-cleanup reuse.
3. Inspect raw-byte/audio consumers before removing any encoded reads; current prepared-audio
   path hook is downstream of InputStream acquisition.
4. Inspect existing deferred handle first-bind/metadata/reload contracts before lazy GPU upload.
5. Integrate coherent results to main, preserve evidence, restore Windows task, stop game, clean builds.

Validation so far: full local Maven verify passed; operator tests 119, 114 passed and 5 skipped
(PowerShell/platform); focused installed barrier/runtime tests passed before adding wait coverage.
