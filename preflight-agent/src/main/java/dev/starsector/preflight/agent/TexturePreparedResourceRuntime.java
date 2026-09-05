package dev.starsector.preflight.agent;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Windows prototype: CPU results travel beside, rather than replace, stock worker results. */
public final class TexturePreparedResourceRuntime {
    public static final String PROPERTY = "preflight.texture.windowsPreparedResources";
    public static final String CLAIM_PROPERTY = "preflight.texture.windowsPreparedResourceClaims";
    public static final String BARRIER_PROPERTY = "preflight.texture.windowsPreparedByteBarrier";
    public static final String PRESTART_PROPERTY = "preflight.texture.windowsPreparedPrestart";
    static final int MAX_OBLIGATIONS = 32_768;
    static final int MAX_RESOURCE_RECORDS = 262_144;
    static final int MAX_DIRECT_DIMENSION = 1_024;
    private static final long MAX_WORKER_DRAIN_NANOS = 5_000_000_000L;
    private static final Object LOCK = new Object();
    private static final Map<String, Obligation> OBLIGATIONS = new HashMap<>();
    private static final Map<String, Completion> COMPLETIONS = new HashMap<>();
    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();
    private static Thread mainThread;
    private static Thread workerThread;
    private static List<?> stockQueue;
    private static Map<?, ?> stockResults;
    private static Object stockSentinel;
    private static Completion claimed;
    private static boolean active;
    private static boolean workerImagePhase;
    private static String waitingPath;
    private static long admitted, published, committed, originalConsumed, discarded, failures, declines;
    private static long direct, coherent, inFlight;
    private static long resourceRecords;
    private static String admissionDecline = "none";
    private static long ceilingDeclines, drainMillis, drainTimeouts;
    private static long queuedClaims, claimFallbacks, claimAbandoned, claimErrors, claimReadNanos;
    private static long lastEntryDeclines, imagePhaseDeferrals, waitPolls, waitNanos;
    private static long resultSignals;
    private static final java.util.Set<String> BYPASSED = new java.util.HashSet<>();
    private static final java.util.Set<String> PRESTART = new java.util.HashSet<>();
    private static long prestartRemoved, prestartTaken, prestartUnused;
    private static boolean bytePhaseComplete;
    private static long barrierRemoved, barrierTaken, barrierUnused;

    private TexturePreparedResourceRuntime() { }

    static boolean requested() {
        return Boolean.getBoolean(PROPERTY)
                && Integer.getInteger(TexturePreparedPrefetchPlan.WINDOWS_WORKERS_PROPERTY, 1) == 1
                && !Boolean.getBoolean(TexturePreparedPrefetchPlan.WINDOWS_SPLIT_QUEUES_PROPERTY);
    }

    static void beginSession() {
        synchronized (LOCK) {
            active = false;
            workerImagePhase = false;
            waitingPath = null;
            mainThread = null;
            workerThread = null;
            stockQueue = null;
            stockResults = null;
            stockSentinel = null;
            claimed = null;
            OBLIGATIONS.clear();
            COMPLETIONS.clear();
            admitted = published = committed = originalConsumed = discarded = failures = declines = 0;
            direct = coherent = inFlight = 0;
            resourceRecords = 0;
            admissionDecline = "none";
            ceilingDeclines = drainMillis = drainTimeouts = 0;
            queuedClaims = claimFallbacks = claimAbandoned = claimErrors = claimReadNanos = 0;
            lastEntryDeclines = imagePhaseDeferrals = waitPolls = waitNanos = 0;
            resultSignals = 0;
            BYPASSED.clear();
            PRESTART.clear();
            prestartRemoved = prestartTaken = prestartUnused = 0;
            bytePhaseComplete = false;
            barrierRemoved = barrierTaken = barrierUnused = 0;
        }
        SCOPE.remove();
    }

    /** Called immediately before the existing worker start, with its actual queue and result map. */
    public static void begin(List<?> resources, Class<?> owner) {
        if (!requested() || !TexturePreparedPixelRuntime.ready()) return;
        try {
            ClassLoader loader = owner.getClassLoader();
            synchronized (LOCK) { resourceRecords = resources.size(); }
            if (resources.size() > MAX_RESOURCE_RECORDS) {
                decline("resource-record-limit");
                return;
            }
            if (!contractsMatch(loader)) {
                decline("contract-mismatch");
                return;
            }
            Class<?> preloader = Class.forName("com.fs.graphics.L", false, loader);
            Field queueField = preloader.getDeclaredField("Õ00000");
            Field resultsField = preloader.getDeclaredField("void");
            Field sentinelField = preloader.getDeclaredField("String");
            queueField.setAccessible(true);
            resultsField.setAccessible(true);
            sentinelField.setAccessible(true);
            List<?> queue = (List<?>) queueField.get(null);
            Map<?, ?> results = (Map<?, ?>) resultsField.get(null);
            Object sentinel = sentinelField.get(null);
            Class<?> resourceClass = Class.forName(
                    "com.fs.starfarer.loading.ResourceLoaderState$Oo", false, loader);
            Field type = resourceClass.getDeclaredField("new");
            Field path = resourceClass.getDeclaredField("o00000");
            Field weight = resourceClass.getDeclaredField("Ó00000");
            type.setAccessible(true);
            path.setAccessible(true);
            weight.setAccessible(true);
            Map<String, Obligation> obligations = new HashMap<>();
            for (Object resource : resources) {
                if (resource == null || resource.getClass() != resourceClass) {
                    decline("resource-class");
                    return;
                }
                Object resourceType = type.get(resource);
                if (!(resourceType instanceof Enum<?> value)) {
                    decline("resource-type");
                    return;
                }
                String logicalPath = (String) path.get(resource);
                // Names and spellings remain exact. The prepared key joins identity, not stock aliases.
                if (!"TEXTURE".equals(value.name()) || logicalPath == null) continue;
                String identity = TextureCompatibilityRuntime.preparedPrefetchKey(logicalPath);
                if (identity != null) obligations.putIfAbsent(logicalPath,
                        new Obligation(value.name(), logicalPath, logicalPath, identity, weight.getInt(resource)));
                if (obligations.size() > MAX_OBLIGATIONS) {
                    decline("obligation-limit");
                    return;
                }
            }
            synchronized (LOCK) {
                if (active) { decline("active-batch"); return; }
                OBLIGATIONS.putAll(obligations);
                admitted += obligations.size();
                mainThread = Thread.currentThread();
                stockQueue = queue;
                stockResults = results;
                stockSentinel = sentinel;
                SCOPE.remove();
                workerImagePhase = false;
                bytePhaseComplete = false;
                active = true;
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (ReflectiveOperationException | RuntimeException error) {
            decline("admission-exception");
        }
    }

    private static void decline(String reason) {
        synchronized (LOCK) {
            declines++;
            admissionDecline = reason;
        }
    }

    static boolean contractsMatch(ClassLoader loader) {
        Map<String, String> hashes = Map.of(
                "com/fs/graphics/L", "9e339c5a0edadebdd81b088e0882f5a00b4696b9f5e862a9beec3ff03c439f3e",
                "com/fs/graphics/L$1", "ac01b004ecbb323ee81cc2cd969b30fe9803db6b8c2622de4b87800e11ad465f",
                "com/fs/graphics/TextureLoader", TextureSpecStoreOverlapPlan.ORIGINAL_SHA256,
                "com/fs/graphics/Object", "b4666849768f27009a32119698d21cf7ef8c78e5bd22b6a8c6520e81708b2162",
                "com/fs/graphics/oOoO", "af75b95d99dcc403ee6487c6f3d8c89e09dcc6bc26214318fa37c3873a513645",
                "com/fs/starfarer/loading/ResourceLoaderState$Oo", "e0df2969d52e0bbc4eae7c3a0c59d4d7a3b498a3f01f859c902a9f3ff00d49b6",
                "com/fs/starfarer/loading/ResourceLoaderState$o", "e34c8c1974f9139bf142e05453491f78366143e5ad0f94d12454e08d0a07f08f");
        try {
            for (var entry : hashes.entrySet()) {
                try (InputStream input = loader.getResourceAsStream(entry.getKey() + ".class")) {
                    if (input == null) return false;
                    byte[] bytes = input.readNBytes(1_048_577);
                    if (bytes.length > 1_048_576 || !entry.getValue().equals(HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(bytes)))) return false;
                }
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    /** No GL or direct-buffer allocation here; the current worker retains all scheduling ownership. */
    public static BufferedImage publish(String path, BufferedImage image) {
        if (image == null) return null;
        synchronized (LOCK) {
            // The pinned worker drains bytes before images. Its first completed image is the
            // admission signal for claims: keep startup's original byte-before-upload boundary.
            if (active && Thread.currentThread() == workerThread) workerImagePhase = true;
            Obligation obligation = active && Thread.currentThread() == workerThread
                    ? OBLIGATIONS.get(path) : null;
            if (obligation != null && !COMPLETIONS.containsKey(path)) {
                COMPLETIONS.put(path, new Completion(obligation, image,
                        TexturePreparedPixelRuntime.isCarrier(image) ? Kind.PREPARED : Kind.ORIGINAL_IMAGE));
                published++;
            }
        }
        return image;
    }

    /** Bound before Thread.start(), so a retiring worker cannot publish into a later batch. */
    public static void worker(Thread worker) {
        synchronized (LOCK) {
            if (active && Thread.currentThread() == mainThread) {
                if (requested() && Boolean.getBoolean(PRESTART_PROPERTY) && workerThread == null
                        && worker != null && worker.getClass() == Thread.class
                        && worker.getState() == Thread.State.NEW) {
                    // This exact hook precedes Thread.start: neither byte nor image work has
                    // begun. Remove only admitted image jobs; retain all byte jobs and unknown
                    // or late images on the unchanged worker. No outside-monitor loop test can
                    // race with removal here, including removal of the last image job.
                    synchronized (stockQueue) {
                        var iterator = stockQueue.iterator();
                        while (iterator.hasNext()) {
                            Object item = iterator.next();
                            Obligation obligation = OBLIGATIONS.get(item);
                            if (obligation != null && !stockResults.containsKey(item)
                                    && obligation.preparedIdentity().equals(
                                            TextureCompatibilityRuntime.preparedPrefetchKey(obligation.path()))) {
                                iterator.remove();
                                PRESTART.add(obligation.path());
                                prestartRemoved++;
                            }
                        }
                    }
                }
                workerThread = worker;
            }
        }
    }

    /** Exact worker boundary: bytes have completed and no image job has been claimed yet.
     * Removing here avoids the stock loop's outside-monitor isEmpty/remove race entirely.
     * If this hook is absent, no jobs are removed and the ordinary typed path remains intact.
     */
    public static void bytePhaseComplete() {
        synchronized (LOCK) {
            if (!requested() || !Boolean.getBoolean(BARRIER_PROPERTY) || !active
                    || Thread.currentThread() != workerThread || bytePhaseComplete) return;
            synchronized (stockQueue) {
                var iterator = stockQueue.iterator();
                while (iterator.hasNext()) {
                    Object item = iterator.next();
                    Obligation obligation = OBLIGATIONS.get(item);
                    if (obligation != null && !stockResults.containsKey(item)
                            && obligation.preparedIdentity().equals(
                                    TextureCompatibilityRuntime.preparedPrefetchKey(obligation.path()))) {
                        iterator.remove();
                        BYPASSED.add(obligation.path());
                        barrierRemoved++;
                    }
                }
            }
            bytePhaseComplete = true;
            LOCK.notifyAll();
        }
    }

    /** Called only after the exact worker's image Map.put, never at decode-return publication. */
    public static void resultReady(String path, BufferedImage image) {
        synchronized (LOCK) {
            if (active && Thread.currentThread() == workerThread && waitingPath != null
                    && waitingPath.equals(path) && stockResults.get(path) == image) {
                resultSignals++;
                LOCK.notifyAll();
            }
        }
    }

    public static void enter(String path, String registrationName) {
        synchronized (LOCK) {
            Scope previous = SCOPE.get();
            Obligation obligation = active && Thread.currentThread() == mainThread && previous == null
                    ? OBLIGATIONS.get(path) : null;
            if (obligation != null && !obligation.registrationName().equals(registrationName)) obligation = null;
            SCOPE.set(new Scope(obligation, previous));
        }
    }

    /** Called above the original image getter, only for a fresh, untransformed batch load. */
    public static Completion take(String path, Object transform, Object existingHandler) {
        Scope scope = SCOPE.get();
        if (scope == null || scope.obligation == null || transform != null || existingHandler != null
                || !scope.obligation.path().equals(path) || scope.completion != null) return null;
        boolean considerClaim = requested() && Boolean.getBoolean(CLAIM_PROPERTY);
        boolean signaledWait = considerClaim || (requested() && Boolean.getBoolean(BARRIER_PROPERTY));
        for (;;) {
            boolean removed = false;
            synchronized (LOCK) {
                if (!active || Thread.currentThread() != mainThread) return null;
                Completion completion = COMPLETIONS.get(path);
                if (completion != null && stockResults.remove(path, completion.image)) {
                    COMPLETIONS.remove(path);
                    scope.completion = completion;
                    claimed = completion;
                    inFlight++;
                    return completion;
                }
                Object ordinary = stockResults.get(path);
                if (ordinary != null && ordinary != stockSentinel) {
                    if (completion != null) {
                        COMPLETIONS.remove(path);
                        discarded++;
                    }
                    return null;
                }
                if (BYPASSED.remove(path)) {
                    barrierTaken++;
                    removed = true;
                }
                if (PRESTART.remove(path)) {
                    prestartTaken++;
                    removed = true;
                }
                if (!removed && considerClaim) {
                    considerClaim = false;
                    // The exact worker removes index zero AND puts its in-flight sentinel under
                    // this monitor. Its preceding isEmpty test is outside the monitor: leave one
                    // entry so a main-thread removal cannot invalidate that test.
                    if (!workerImagePhase) {
                        imagePhaseDeferrals++;
                    } else synchronized (stockQueue) {
                        if (!stockResults.containsKey(path)
                                && scope.obligation.preparedIdentity().equals(
                                        TextureCompatibilityRuntime.preparedPrefetchKey(path))) {
                            if (stockQueue.size() > 1) {
                                removed = stockQueue.remove(path);
                                if (removed) queuedClaims++;
                            } else if (stockQueue.contains(path)) {
                                lastEntryDeclines++;
                            }
                        }
                    }
                }
                // Match the exact getter's queue/in-flight test and its 10ms polling schedule.
                if (!removed && !stockQueue.contains(path) && !stockResults.containsKey(path)) return null;
                if (!removed && signaledWait) {
                    long started = System.nanoTime();
                    waitingPath = path;
                    try {
                        // Checking the result and registering the wait under the same lock as the
                        // post-put signal prevents a lost wakeup. Keep stock's timeout as fallback
                        // if the worker hook is unavailable or a result fails to publish.
                        LOCK.wait(10L);
                    } catch (InterruptedException interrupted) {
                        return null;
                    } finally {
                        waitingPath = null;
                        waitPolls++;
                        waitNanos += Math.max(0L, System.nanoTime() - started);
                    }
                    continue;
                }
            }
            if (removed) return loadClaimed(scope);
            long started = System.nanoTime();
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                // Stock getter consumes this interrupt and returns null for original decode.
                return null;
            } finally {
                synchronized (LOCK) {
                    waitPolls++;
                    waitNanos += Math.max(0L, System.nanoTime() - started);
                }
            }
        }
    }

    /** The removed stock job is retired even on a cache miss: the original getter now misses
     * immediately and the original decoder owns fallback. Never requeue or publish into stock maps.
     */
    private static Completion loadClaimed(Scope scope) {
        long started = System.nanoTime();
        try {
            BufferedImage image = TexturePreparedStagingRuntime.take(scope.obligation.path());
            if (image == null) {
                image = TexturePreparedPixelRuntime.load(scope.obligation.path());
            }
            synchronized (LOCK) {
                if (!active || SCOPE.get() != scope
                        || OBLIGATIONS.get(scope.obligation.path()) != scope.obligation) {
                    claimAbandoned++;
                    return null;
                }
                if (image == null) {
                    claimFallbacks++;
                    return null;
                }
                Completion completion = new Completion(scope.obligation, image, Kind.PREPARED);
                scope.completion = completion;
                claimed = completion;
                published++;
                inFlight++;
                return completion;
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            synchronized (LOCK) { claimErrors++; }
            throw fatal;
        } catch (RuntimeException error) {
            synchronized (LOCK) { claimErrors++; }
            return null;
        } finally {
            synchronized (LOCK) { claimReadNanos += Math.max(0L, System.nanoTime() - started); }
        }
    }

    /** The original getter still owns ordinary/transformed/late consumers and their result removal. */
    public static void originalConsumed(String path, BufferedImage image) {
        synchronized (LOCK) {
            Completion completion = COMPLETIONS.get(path);
            if (completion != null && completion.image == image) {
                COMPLETIONS.remove(path);
                originalConsumed++;
            }
        }
    }

    public static void exit(boolean success) {
        Scope scope = SCOPE.get();
        if (scope == null || scope.previous == null) SCOPE.remove();
        else SCOPE.set(scope.previous);
        if (scope == null || scope.completion == null) return;
        synchronized (LOCK) {
            if (scope.completion.retired) return;
            scope.completion.retired = true;
            claimed = null;
            inFlight--;
            if (success) committed++; else failures++;
        }
    }

    /** Finish the current worker's bounded late queue before stock interrupt/retention cleanup.
     * No jobs are reordered and no worker is added. In particular, an in-flight pack read must
     * finish before interruption can close its shared FileChannel and erase the late-cache benefit.
     */
    public static void finishWorker() {
        long started = System.nanoTime();
        for (;;) {
            synchronized (LOCK) {
                if (!active || Thread.currentThread() != mainThread) return;
                if (workerThread == null || !workerThread.isAlive()
                        || (stockQueue.isEmpty() && !stockResults.containsValue(stockSentinel))) break;
                if (System.nanoTime() - started >= MAX_WORKER_DRAIN_NANOS) {
                    drainTimeouts++;
                    break;
                }
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        synchronized (LOCK) { drainMillis += (System.nanoTime() - started) / 1_000_000L; }
        end();
    }

    /** Stop accepts no later worker publications; the stock stop still owns its maps and late retention. */
    public static void end() {
        synchronized (LOCK) {
            active = false;
            discarded += COMPLETIONS.size();
            COMPLETIONS.clear();
            OBLIGATIONS.clear();
            barrierUnused += BYPASSED.size();
            BYPASSED.clear();
            prestartUnused += PRESTART.size();
            PRESTART.clear();
            if (claimed != null) {
                claimed.retired = true;
                claimed = null;
                discarded++;
                inFlight--;
            }
            if (Thread.currentThread() == mainThread) SCOPE.remove();
            stockQueue = null;
            stockResults = null;
            stockSentinel = null;
            workerThread = null;
            LOCK.notifyAll();
        }
    }

    static Map<String, Object> telemetry() {
        synchronized (LOCK) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("requested", requested());
            values.put("active", active);
            values.put("byteBarrierRequested", requested() && Boolean.getBoolean(BARRIER_PROPERTY));
            values.put("bytePhaseComplete", bytePhaseComplete);
            values.put("barrierRemoved", barrierRemoved);
            values.put("barrierTaken", barrierTaken);
            values.put("barrierUnused", barrierUnused);
            values.put("barrierPending", BYPASSED.size());
            values.put("prestartRequested", requested() && Boolean.getBoolean(PRESTART_PROPERTY));
            values.put("prestartRemoved", prestartRemoved);
            values.put("prestartTaken", prestartTaken);
            values.put("prestartUnused", prestartUnused);
            values.put("prestartPending", PRESTART.size());
            values.put("admitted", admitted);
            values.put("resourceRecords", resourceRecords);
            values.put("admissionDecline", admissionDecline);
            values.put("directDimensionCeiling", MAX_DIRECT_DIMENSION);
            values.put("ceilingDeclines", ceilingDeclines);
            values.put("workerDrainMillis", drainMillis);
            values.put("workerDrainTimeouts", drainTimeouts);
            values.put("queuedClaimsRequested", requested() && Boolean.getBoolean(CLAIM_PROPERTY));
            values.put("queuedClaims", queuedClaims);
            values.put("claimFallbacks", claimFallbacks);
            values.put("claimAbandoned", claimAbandoned);
            values.put("claimErrors", claimErrors);
            values.put("claimReadMillis", claimReadNanos / 1_000_000L);
            values.put("lastEntryDeclines", lastEntryDeclines);
            values.put("workerImagePhaseObserved", workerImagePhase);
            values.put("imagePhaseDeferrals", imagePhaseDeferrals);
            values.put("waitPolls", waitPolls);
            values.put("waitMillis", waitNanos / 1_000_000L);
            values.put("resultSignals", resultSignals);
            values.put("published", published);
            values.put("committed", committed);
            values.put("originalConsumed", originalConsumed);
            values.put("discarded", discarded);
            values.put("failures", failures);
            values.put("declines", declines);
            values.put("pending", COMPLETIONS.size());
            values.put("inFlight", inFlight);
            values.put("direct", direct);
            values.put("coherent", coherent);
            return Map.copyOf(values);
        }
    }

    record Obligation(String type, String path, String registrationName, String preparedIdentity, int weight) { }
    public enum Kind { PREPARED, ORIGINAL_IMAGE }
    public static final class Completion {
        private final Obligation obligation;
        private final BufferedImage image;
        private final Kind kind;
        private boolean prepareAttempted;
        private boolean retired;
        Completion(Obligation obligation, BufferedImage image, Kind kind) {
            this.obligation = obligation;
            this.image = image;
            this.kind = kind;
        }
        public BufferedImage image() { return image; }
        public Kind kind() { return kind; }
        public TexturePreparedPixelRuntime.PreparedPixel prepare() {
            requireOwner();
            if (prepareAttempted) throw new IllegalStateException("Repeated texture commit");
            prepareAttempted = true;
            boolean withinCeiling = image.getWidth() <= MAX_DIRECT_DIMENSION
                    && image.getHeight() <= MAX_DIRECT_DIMENSION;
            TexturePreparedPixelRuntime.PreparedPixel pixel = kind == Kind.PREPARED && withinCeiling
                    ? TexturePreparedPixelRuntime.prepare(image) : null;
            synchronized (LOCK) {
                if (kind == Kind.PREPARED && !withinCeiling) ceilingDeclines++;
                if (pixel == null) coherent++; else direct++;
            }
            return pixel;
        }
        public void creditOriginalFallback() {
            requireOwner();
            if (!prepareAttempted) throw new IllegalStateException("Unprepared coherent texture commit");
            if (kind == Kind.PREPARED) TexturePreparedPixelRuntime.creditPreparedResourceFallback(image);
        }
        private void requireOwner() {
            synchronized (LOCK) {
                if (!active || retired || Thread.currentThread() != mainThread || SCOPE.get() == null
                        || SCOPE.get().completion != this)
                    throw new IllegalStateException("Unowned texture commit");
            }
        }
    }
    private static final class Scope {
        private final Obligation obligation;
        private Completion completion;
        private final Scope previous;
        private Scope(Obligation obligation, Scope previous) {
            this.obligation = obligation;
            this.previous = previous;
        }
    }
}
