# Scoped true-size prepared textures, 2026-08-23

## Question

The historical 15.25-second launch used true-size prepared texture uploads, which avoided about
949 MB of zero padding. The option was removed from Recommended because the dimension bypass was a
process-wide property. If one prepared lookup missed after the property opened, Starsector could
allocate the original image at its true size and then supply the original padded buffer. That size
mismatch could corrupt a texture or stop the launch.

Can the same upload saving be restored without changing any fallback allocation?

## Boundary

The prepared-pixel runtime now grants two dimension-fold claims only after it has registered a
verified true-size upload buffer for the current loader thread. Two is the exact number of folds in
the reviewed installed loader. Cleanup removes any unused claims with the buffer.

A missing, corrupt, unsupported, or resource-limited prepared entry never registers that buffer.
Its fold therefore remains Starsector's original power-of-two fold even while Recommended requests
true-size prepared uploads for entries that do hit.

The existing gates still apply before any claim exists:

- the exact texture loader and composed fold transform must match;
- the live OpenGL context must support non-power-of-two textures;
- the prepared texture entry must pass its normal profile, pack, checksum, and size checks;
- the direct-buffer reservation must succeed.

## Live mixed-hit result

Development JAR SHA-256:
`c6ac86f10974fcef09a1f1281729f67997b1c2d7d06dc5d22851f4b7a34e9090`

Prepared cache: exact Balanced pack, 15,469 hits and 3 ordinary `entry-missing` fallbacks.

The probed launch reached the main menu and stopped cleanly. Its final telemetry reported:

- 11,472 true-size NPOT uploads;
- 22,944 scoped dimension bypasses, exactly two per true-size upload;
- 8,002 original dimension folds for entries without a true-size claim;
- 949,376,521 padding bytes avoided;
- 15,469 releases with zero active or pending prepared buffers at shutdown;
- zero prepared-pixel fallbacks, pack failures, corruptions, and internal errors.

The three missing entries used the source path without inheriting a dimension bypass. This is the
mixed hit and miss condition the old global property could not make safe.

## Timed diagnostic

Session: `20260823-021207`

Protocol: direct unattended launch, current `--fast`, 180-second cooldown before each launch,
one condition, three rounds. The earlier profiled launch served as the settling run.

| Run | Main menu |
| ---: | ---: |
| 1 | 16.63s |
| 2 | 15.84s |
| 3 | 15.82s |

Median: **15.84 seconds**. Minimum: **15.82 seconds**.

This is a diagnostic recovery check rather than a new public timing claim. A five-run candidate
campaign still belongs to the eventual packaged release evidence. It does show that the low-15
regime remains reachable on current code while all observed prepared misses preserve the original
allocation path.
