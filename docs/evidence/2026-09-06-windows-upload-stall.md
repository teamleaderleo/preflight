# Windows native upload stall investigation

Objective: identify and fix the native upload stall before selecting the packed-raster performance
lead. Owner: current Codex task. Phase: validating low-overhead pending-upload diagnostics.
Finish: test a justified fix, integrate main, leave the ordinary Windows task Ready with no actors,
and preserve exact evidence while retiring disposable builds. Do not claim reliability from one fast run.

Baseline main `23819ab16f76c10941a3d8d429226d848ed5173b`, installed JAR
`369d43b415829c5082c29d1762adb9d8fdb73f6cf73d6406087740bd38534cb9`.
The packed-raster lead from `10162d70…` produced 18.113 / 17.537 / 16.690 s menus, then stalled
on the first repeat. Restoring it here is for investigation, not acceptance of that failed trial.

The existing upload checkpoint writes a file before every native call. Replace that diagnostic
behavior with one metadata-only pending record and a daemon observer that checks every ten seconds.
An attempt pending at least ten seconds writes once; successful uploads have no checkpoint I/O.
The record retains no ByteBuffer reference, changes no buffer state, and performs no GL calls.
Session replacement retires the old observer and clears pending state. Ordinary launches have no
checkpoint observer. Tests cover delayed publication, single publication, buffer bounds and state,
completion clearing and session replacement. The stock resource worker count remains one.
