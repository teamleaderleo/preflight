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
    static final int MAX_OBLIGATIONS = 32_768;
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
    private static long admitted, published, committed, originalConsumed, discarded, failures, declines;
    private static long direct, coherent, inFlight;

    private TexturePreparedResourceRuntime() { }

    static boolean requested() {
        return Boolean.getBoolean(PROPERTY)
                && Integer.getInteger(TexturePreparedPrefetchPlan.WINDOWS_WORKERS_PROPERTY, 1) == 1
                && !Boolean.getBoolean(TexturePreparedPrefetchPlan.WINDOWS_SPLIT_QUEUES_PROPERTY);
    }

    static void beginSession() {
        synchronized (LOCK) {
            active = false;
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
        }
        SCOPE.remove();
    }

    /** Called immediately before the existing worker start, with its actual queue and result map. */
    public static void begin(List<?> resources, Class<?> owner) {
        if (!requested() || !TexturePreparedPixelRuntime.ready()) return;
        try {
            ClassLoader loader = owner.getClassLoader();
            if (!contractsMatch(loader) || resources.size() > MAX_OBLIGATIONS) {
                synchronized (LOCK) { declines++; }
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
                if (resource == null || resource.getClass() != resourceClass) return;
                Object resourceType = type.get(resource);
                if (!(resourceType instanceof Enum<?> value)) return;
                String logicalPath = (String) path.get(resource);
                // Names and spellings remain exact. The prepared key joins identity, not stock aliases.
                if (!"TEXTURE".equals(value.name()) || logicalPath == null) continue;
                String identity = TextureCompatibilityRuntime.preparedPrefetchKey(logicalPath);
                if (identity != null) obligations.putIfAbsent(logicalPath,
                        new Obligation(value.name(), logicalPath, logicalPath, identity, weight.getInt(resource)));
            }
            synchronized (LOCK) {
                if (active) { declines++; return; }
                OBLIGATIONS.putAll(obligations);
                admitted += obligations.size();
                mainThread = Thread.currentThread();
                stockQueue = queue;
                stockResults = results;
                stockSentinel = sentinel;
                SCOPE.remove();
                active = true;
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (ReflectiveOperationException | RuntimeException error) {
            synchronized (LOCK) { declines++; }
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
            if (active && Thread.currentThread() == mainThread) workerThread = worker;
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
        for (;;) {
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
                // Match the exact getter's queue/in-flight test and its 10ms polling schedule.
                if (!stockQueue.contains(path) && !stockResults.containsKey(path)) return null;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                // Stock getter consumes this interrupt and returns null for original decode.
                return null;
            }
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

    /** Stop accepts no later worker publications; the stock stop still owns its maps and late retention. */
    public static void end() {
        synchronized (LOCK) {
            active = false;
            discarded += COMPLETIONS.size();
            COMPLETIONS.clear();
            OBLIGATIONS.clear();
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
        }
    }

    static Map<String, Object> telemetry() {
        synchronized (LOCK) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("requested", requested());
            values.put("active", active);
            values.put("admitted", admitted);
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
            TexturePreparedPixelRuntime.PreparedPixel pixel = kind == Kind.PREPARED
                    ? TexturePreparedPixelRuntime.prepare(image) : null;
            synchronized (LOCK) {
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
