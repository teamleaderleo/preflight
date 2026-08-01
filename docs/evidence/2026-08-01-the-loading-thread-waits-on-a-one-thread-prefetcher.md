# The loading thread waits 27 seconds on a one-thread prefetcher

**Date:** 2026-08-01
**Install:** Starsector 0.98a-RC8, 77 enabled mods, macOS 15, M5 MacBook Air (10 cores), 24 GB
**Recording:** `20260801-100133/runs/profile-1/startup.jfr`, SAMPLE mode, direct protocol, compatibility textures
**Status:** supersedes the conclusion of [what the load is actually waiting for](2026-08-01-what-the-load-is-actually-waiting-for.md)

That document said the loading thread never blocks, that the load is one long serial chain, and
that the idle-core opportunity "is not reachable from where Preflight sits." The first claim was
an artifact of my own reporting script. The third follows from the first and is also wrong.

**The loading thread spends 27 of the load's 96 seconds asleep, and every one of those seconds is
spent waiting on a single background thread decoding PNGs.**

## Two defects in the instrument

### The recording's clock runs at 0.401x real time

Starsector launches with `-XX:+UseFastUnorderedTimeStamps`. Under that flag JFR's
tick-to-nanosecond conversion is wrong by a constant factor, and **every duration and every event
timestamp in the recording is compressed by it.** On this install the factor is **2.4907**.

Four independent measurements agree, and no two of them share a mechanism:

| check | expected | recorded | implied factor |
| --- | --- | --- | --- |
| `jdk.CPULoad` period, fixed at 1000 ms by the game JDK's own `profile.jfc` | 1.000s | 0.4015s | 2.4907 |
| a `Thread.sleep(10)` on the loading thread | >= 10ms | 4.744ms median | ~2.5 (a sleep cannot return early) |
| chunk header duration against the span of its events | 99s | 38.5s | 2.506 |
| corrected event span against the game's own log-derived load time | 97.8s | 38.5s | 2.538 |

The second is the one that makes this a defect rather than an interpretation: `Thread.sleep(10)`
returning in 4.7ms is not a thing that happens. The fourth is the one that makes the correction
trustworthy: Starsector's log timestamps come from `System.currentTimeMillis` and know nothing
about JFR, and the corrected recording lands within 2% of them.

This is why the recording *looked* truncated at 40 seconds. It was never truncated. It covers the
whole load, on a clock running at four tenths speed.

**What this voids:** every duration previously read out of a Starsector JFR recording, including
the 1.01s attributed to the per-lookup SHA-256, which needs re-measuring. **What it does not
void:** every share, ratio and sample count. Those are counts of events, and counting does not
depend on the clock. The profile attributions the project has been steering by — texture
conversion at 34-40% of the loading thread, PNG decode at 13-16%, `machineTotal` at 27.8% — all
stand.

### The report hid the one thread that mattered

`starsector_critical_path.py` printed the eight threads with the most blocked time. Six of them
are daemons that spend their entire life parked on an empty queue — Java2D Queue Flusher,
Common-Cleaner, Finalizer, Timer-0, Java2D Disposer — and they block for the whole run by
construction. Ranked by raw blocked time the loading thread comes **ninth**, and the `[:8]` cut
dropped it off the page. I read that absence as a finding and published it.

Total blocked time cannot distinguish a thread waiting for work from a thread waiting for another
thread's result. **The stack can, and it is now printed for every row:** the idle daemons all sit
at `Object.wait <- ReferenceQueue.remove`, and the loading thread sits somewhere else entirely.

## What the loading thread is waiting for

```
main   27.4s   ThreadSleep:27.2s   <-- loading thread
    at java/lang/Thread.sleep <- com/fs/graphics/L.class
       <- com/fs/graphics/TextureLoader.Ô00000 <- com/fs/graphics/TextureLoader.o00000
```

2,394 sleep events, all of them at that one site.

`com/fs/graphics/L` is not a registry, which is what the previous document guessed from a
one-frame stack. It is **Starsector's own background image prefetcher**, and reading it locally
(interoperability inspection only; nothing from the game's jars is reproduced here) its shape is:

- two queues, each a `Collections.synchronizedList(LinkedList)` — one for decoded images, one for
  raw bytes — plus a `ConcurrentHashMap` of results for each;
- `ResourceLoaderState` walks its whole resource list, enqueues every image resource, and then
  starts **exactly one** thread to drain both queues;
- that thread pops the head, publishes a sentinel, decodes through `ImageIO`, and replaces the
  sentinel with the image;
- the consumer, called from `TextureLoader`, first asks whether the path is queued — a
  `contains()` on a `LinkedList`, which is the O(n) scan previously measured at 6.4% of the
  loading thread — and if it is, polls the result map, **sleeping 10ms at a time until the one
  decoder thread gets to it.**

So the game already has an asynchronous prefetch pipeline. It is one thread wide, on a ten-core
machine, feeding a loader that outruns it.

The stall is not spread evenly. In wall-clock seconds of the load:

| window | loading thread asleep |
| --- | --- |
| 0-20s | 0.0s |
| 20-30s | 3.1s |
| 30-40s | 5.1s |
| **40-50s** | **8.3s of 10** |
| 50-60s | 6.8s |
| 60-70s | 3.9s |
| 70-96s | 0.0s |

This also explains something the previous campaign wrote down as unexplained: *"One window is
conspicuous: 40-45s logs almost nothing at all."* The game logs almost nothing there because the
thread that does the logging is asleep 83% of that window.

## Why Preflight gets none of this back

The compatibility rewrite inserts our cache lookup at the point where the prefetcher **misses** —
`TextureCompatibilityPlan` deliberately finds the branch after the `L.class` call and splices in
there. That was a conservative choice: take over only the path the game would have decoded
synchronously, and leave everything else exactly as it was.

The consequence is that **our cache is on the wrong side of the wait.** For any image the
prefetcher owns, the loading thread blocks on the single decoder thread and our cache is never
consulted — even though the decoded pixels are already sitting in it.

The telemetry shows this plainly. Our hook was reached **6,654 times** and served 6,651 of them.
The manifest holds **32,917 textures**, the whole profile. The gap is the set the prefetcher
answered first, and the 27.2 seconds is what the loading thread paid to wait for it.

That is the real answer to why the measured win is 1.5%. Not that the load is irreducibly serial —
it is that we optimised the branch the game takes when its own prefetcher has already lost the race.

## What to do

1. **Move the cache lookup ahead of the prefetcher call.** On a hit, the loading thread never
   consults the queue and never sleeps: no O(n) scan, no 10ms polls. This is the same one-method
   rewrite already in place, moved a few instructions earlier, and it is where the 27 seconds is.
   Two things need care and neither is exotic:
   - images the prefetcher has already been handed still get decoded by it and left in its result
     map, so the map must not be allowed to accumulate what nobody collects;
   - cleanest is to skip *enqueueing* what the manifest can serve, which keeps the queue short,
     keeps the scan cheap, wastes no background decode, and leaves the game's own path exactly
     intact for everything we cannot serve.
2. **Re-measure the per-lookup SHA-256.** Its 1.01s came off the broken clock; the true figure is
   probably nearer 2.5s, which makes it a larger share of why the compatibility cache is a net
   regression than previously recorded.
3. **Widening the prefetcher is a separate, riskier idea worth noting.** Its drain loop is close to
   thread-safe already, and more threads would help vanilla players with no cache at all. It is
   also a change to the game's own concurrency with a plausible failure mode — a thread dying
   between claiming an entry and publishing it would hang a loader polling forever — so it is not
   a fail-open change and should not be attempted before item 1.

## What still stands from the superseded document

The machine is 27.8% busy; stop-the-world GC is 0.00s; GPU upload is ~1% of samples; the JSON/spec
path is comparable to the texture path in both time and allocation and remains entirely
unaddressed. Those are all counts and ratios.

What changes is the conclusion drawn from them. Seven idle cores next to a loading thread that
sleeps for 27 seconds is not a serial chain that cannot be split. It is a pipeline that is one
thread too narrow, and the side of it Preflight already sits on can serve those reads immediately.
