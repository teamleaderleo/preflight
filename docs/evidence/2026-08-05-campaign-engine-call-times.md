# Attribute campaign frames inside the vanilla engine

Date: 2026-08-05

Status: implementation and exact installed-archive verification complete; live pilot pending

## Why another layer was necessary

The first live call-time pilot proved two real medium hitches: Nexerelin route spawn/despawn reached
35.155ms inside a 50.834ms frame, and diplomacy advance reached 36.253ms inside a 53.250ms frame.
The other four reviewed seams were smaller. Most frames over 100ms contained none of the six timed
calls, so optimizing those two isolated calls would not explain the general campaign tail.

The next exact owner is vanilla `CampaignEngine.advance(float, B)`. It directly invokes the major
managers, advances current and background locations, and owns both lists of engine-level
`EveryFrameScript` instances. Timing these call sites avoids the unsound practice of assigning a
silent frame interval to the nearest log message.

## Exact target and call shape

Installed `com/fs/starfarer/campaign/CampaignEngine.class` has SHA-256
`99cb7c6a7aa026ec3f2fe4439d66b7b8cd24e4068ca24ffae89eac7421cabf6d` in the reviewed
`starfarer_obf.jar`. Plan `campaign-engine-call-time-probe-v1` requires the exact class identity,
Java 17 class version, and method descriptor `(FLcom/fs/starfarer/util/super/B;)V`.

It also requires the complete reviewed invocation counts before transforming:

- one each for intel, campaign events, important people, persistent UI data, economy, factions,
  and campaign help;
- two memory advances;
- ten location/hyperspace advances across normal, paused, fast, current, and background paths; and
- two `EveryFrameScript.advance(float)` calls, one for persistent scripts and one for transient
  scripts.

Any changed hash, descriptor, count, or pre-existing runtime call declines the adapter instead of
guessing at a future patch.

## Runtime behavior

Every reviewed call stores its receiver and arguments in fresh locals, starts the timer, restores
the original operand stack, and invokes the original instruction. Normal and exceptional exits
both close the timer; exceptional exits rethrow the original `Throwable`. Runtime diagnostics
contain their own non-fatal failures and propagate VM-fatal errors.

Manager and location categories use fixed primitive counters. Engine-level scripts use a
session-scoped `ClassValue` to allocate one counter object per concrete class, then retain count,
total, average, maximum and threshold counts. The maximum call also retains its end epoch so it can
be joined to the frame probe's exact worst-frame timestamps. No class attribution or timing runs
unless `preflight.frameTimes=true` was explicitly requested.

## Verification

Runtime tests cover fixed-phase and concrete-script grouping. Shape tests pin all 21 reviewed call
sites and reject a missing call, disabled runtime, wrong identity, or a second transformation. The
exact installed `starfarer_obf.jar` transforms to 19 fixed-phase entries, two per-class script
entries, and exception-safe exits for every call. Full `mvn verify` passes. A live campaign pilot is
the remaining gate.
