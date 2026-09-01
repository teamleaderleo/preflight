# Issue 1153 frame-sync experiment

This experiment ports the narrow frame-pacing idea from `Halke1986/starsector-render` without taking its render-thread rewrite.

Vanilla `BaseGameState.traverse()` computes a remaining frame interval in seconds, then truncates it through `seconds * 1000 -> int -> long -> Thread.sleep(long)`. The candidate replaces only that reviewed bytecode block. It sleeps the coarse portion and spins through the final margin against a `System.nanoTime()` deadline.

## Activation

The experiment is disabled by default. Use an exact, source-bound external adapter target for the installed `com/fs/starfarer/BaseGameState` class with the existing `lwjgl-display-frame-time-probe-v1` plan ID, plus:

```
-Dpreflight.frameSync=true
-Dpreflight.frameSync.report=/absolute/path/frame-sync.json
```

The temporary reuse of the frame-time plan ID keeps this lane isolated from the central plan registry while live results are pending. A retained candidate should receive its own plan ID before merge.

Optional spin margin override, in nanoseconds:

```
-Dpreflight.frameSync.spinMarginNanos=2000000
```

The default 2 ms margin follows starsector-render's sleep-then-spin pacing. The runtime report records precise/fallback calls, requested wait, coarse-sleep time, spin time, total wait, overshoot, and maxima.

## Exact-target requirement

Do not guess a class hash, archive hash, loader, or source suffix. Run the adapter discovery pass on the installation, copy the reported `BaseGameState` identity into the external target file, and keep the source binding fields. The bytecode plan adds another gate: it requires exactly one straight-line sequence equivalent to:

```
FLOAD seconds
LDC 1000f
FMUL
F2I
ISTORE millis
ILOAD millis
I2L
INVOKESTATIC java/lang/Thread.sleep (J)V
```

Any changed or duplicate sequence declines the rewrite.

## Gameplay measurement

Use the ordinary combat route and the symmetric 1,040-DP route from #449/#1152. Compare at least average FPS, p50/p95/p99 frame time, 1% low, >50 ms frames, >100 ms frames, and workload fingerprint. Keep frame-time probe overhead identical between A and B.
