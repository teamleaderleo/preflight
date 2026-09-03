# Windows startup cohort runtime-state semantics

`run-windows-startup-cohort.ps1` consumes the live runtime-state packet emitted by the Preflight
agent for Preflight-backed conditions.

## Runtime-state v2

`starsector-preflight-runtime-state-v2` is the current output format.

- `mainMenuInteractiveAt` is the first reviewed usable title/menu boundary. On the exact Windows
  target this is successful completion of the title object's `show()V` lifecycle, before the first
  subsequent reviewed title advance.
- `mainMenuOverlayRemovedAt` is the later low-frequency observation that the exact `Preloading...`
  label removal occurred. It is diagnostic only and never controls semantic state.
- `processStartToMainMenuInteractiveMs` derived from a v2 packet is therefore process start to first
  reviewed menu usability.

The visible `Preloading...` blink may still be running at the v2 interactive timestamp. The cohort
must never wait for overlay removal as an acceptance condition for menu usability.

## Historical runtime-state v1

`starsector-preflight-runtime-state-v1` remains historical input. On the reviewed Windows target its
field named `mainMenuInteractiveAt` was published immediately after `Preloading...` label removal,
behind `blink(3.0f, 10.0f)`. Treat that field as the former overlay-removal endpoint.

Historical raw `processStartToMainMenuInteractiveMs` values from v1 stay unchanged in archives. A
v1 delta must never be presented as genuine time to first useful menu interaction. Comparisons that
used that endpoint, including standalone Preflight versus Preflight + Fast Rendering semantic
comparisons, require a new v2 measurement before supporting a first-usability claim.

## Runner changes required by the format transition

New v2 packets keep the existing field name for the corrected usability timestamp, so the cohort's
existing live duration calculation automatically targets the repaired boundary when it runs a v2
agent. Consumers that ingest archived packets must inspect `format` first and classify v1 separately;
the Java desktop reader exposes v1's old field explicitly as a legacy overlay-removal timestamp and
exposes first-usability timing only for v2.

This semantic repair changes no title timing, rendering, workers, countdown, click/input behavior,
or performance policy. It also makes no new performance claim.
