# Native macOS automation boundary — 2026-08-09

The supported macOS desktop path no longer asks a disposable bundled Java executable to own desktop
automation. The packaged Preflight host creates an ephemeral loopback listener and a random 256-bit
capability for each readiness probe or automated test, passes them only to the bundled engine child,
and closes the listener when that child ends.

The native protocol is deliberately smaller than the Java driver's internal interface. It accepts
only the permission probe, exact-PID window bounds, activation, observation, the reviewed relative
Continue click, seven reviewed keys, safe release, Command-Q, and a capture confined to
`desktop-smoke.png` under the canonical run directory. Requests reject unknown fields and
operations, nonpositive PIDs, other targets and keys, missing parameters, incorrect capabilities,
and oversized input. The host constructs every AppleScript itself; Java cannot supply script text,
screen coordinates, a host, or an output path.

Offline checks cover the closed operation list, exact-PID script construction, authorization
shape, loopback-only Java endpoint validation, response validation, and the native package's
System Events disclosure. The installed-package exercise starts the real `.app` in a no-game probe
mode and traverses `.app → native bridge → bundled engine`. A ready result must name
`macos-preflight-native-pid`; an unavailable result must name the Preflight application and must not
name `runtime/bin/java`. The game was not launched for this boundary check.

The first installed-copy replay exposed an older native-host path bug: after `Preflight.app` moved
out of the build directory, Tauri's resource resolver didn't find the bundled engine even though the
bytes were present. Engine discovery now has a macOS-only fallback anchored at the canonical native
executable and confined to its sibling `Contents/Resources` tree. Rebuilding the DMG and repeating
the complete mounted-image, copied-app, native-probe, synthetic preparation, dry launch-plan,
diagnostics, removal, and data-retention exercise passed. The native probe returned the expected
unavailable result naming the Preflight application.

This changes the stable permission subject without weakening the runtime identity checks. Java
still reloads the recorded game PID and start instant before every action, the native host resolves
that numeric PID again through System Events, and the runner still owns deadlines, cleanup, and
evidence. An unsigned or differently signed development build can require a fresh macOS permission
grant because privacy approval follows application code identity. The remaining acceptance gate is
one isolated action run from a packaged build with Accessibility and Screen Recording granted to
Preflight.
