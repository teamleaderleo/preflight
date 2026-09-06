package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.PreparedTexture;
import java.awt.Color;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Fail-open carrier bridge from Preflight's prepared store into Fast Rendering 0.8.4. */
public final class FastRenderingPreparedTextureRuntime {
    static final String PLAN_ID = "fast-rendering-0.8.4-prepared-texture-v1";
    static final String PORT_PLAN_ID = "fast-rendering-0.8.7-port-prepared-texture-v1";
    static final String PORT_PROPERTY = "preflight.texture.fastRenderingPortPrepared";
    static final String PORT_TEXTURE_DATA = "com.genir.renderer.overrides.loading.textures.TextureData";
    static final String ENABLED_PROPERTY = "preflight.texture.fastRenderingPrepared";
    static final String TEXTURE_DATA = "com.genir.renderer.overrides.loading.TextureData";
    static final String RESOURCE_HANDLE = "com.genir.renderer.overrides.loading.ResourceHandle";
    static final int MAX_INTERNAL_ERRORS = 8;
    private static final int MAX_THREAD_IDENTITIES = 8;

    private static final LongAdder ATTEMPTS = new LongAdder();
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder HIT_BYTES = new LongAdder();
    private static final LongAdder LOOKUP_MISSES = new LongAdder();
    private static final LongAdder DISABLED_DECLINES = new LongAdder();
    private static final LongAdder TYPE_DECLINES = new LongAdder();
    private static final LongAdder RESOURCE_DECLINES = new LongAdder();
    private static final LongAdder LAYOUT_DECLINES = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final LongAdder TOTAL_NANOS = new LongAdder();
    private static final AtomicInteger INTERNAL_ERRORS = new AtomicInteger();
    private static final AtomicBoolean CIRCUIT_BREAKER = new AtomicBoolean();
    private static final AtomicLong SESSION = new AtomicLong();
    private static final ThreadLocal<Long> OBSERVED_SESSION =
            ThreadLocal.withInitial(() -> Long.MIN_VALUE);
    private static final Object THREAD_LOCK = new Object();
    private static final LinkedHashMap<String, Long> THREADS = new LinkedHashMap<>();
    private static volatile Shape shape;
    private static volatile int installedTargets;

    private FastRenderingPreparedTextureRuntime() {
    }

    static void beginSession() {
        ATTEMPTS.reset();
        HITS.reset();
        HIT_BYTES.reset();
        LOOKUP_MISSES.reset();
        DISABLED_DECLINES.reset();
        TYPE_DECLINES.reset();
        RESOURCE_DECLINES.reset();
        LAYOUT_DECLINES.reset();
        FAILURES.reset();
        TOTAL_NANOS.reset();
        INTERNAL_ERRORS.set(0);
        CIRCUIT_BREAKER.set(false);
        SESSION.incrementAndGet();
        synchronized (THREAD_LOCK) {
            THREADS.clear();
        }
        shape = null;
        installedTargets = 0;
    }

    static boolean requested() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    static boolean ready() {
        return requested() && TextureCompatibilityRuntime.ready() && !CIRCUIT_BREAKER.get();
    }

    /**
     * Returns a Fast Rendering {@code TextureData}, or {@code null} to retain its original loader.
     *
     * <p>The third argument deliberately stays {@code Object}: the agent is built without a Fast
     * Rendering dependency. Its exact class and archive are independently pinned by the adapter
     * target before this method can be woven.
     */
    public static Object load(String type, String logicalPath, Object resource) {
        return load(type, logicalPath, resource, false);
    }

    /** Separate opt-in while native port verification is outstanding. */
    public static Object loadPort(String type, String logicalPath, Object resource) {
        return load(type, logicalPath, resource, true);
    }

    static boolean portReady() {
        return Boolean.getBoolean(PORT_PROPERTY) && ready();
    }

    private static Object load(String type, String logicalPath, Object resource, boolean port) {
        long started = System.nanoTime();
        ATTEMPTS.increment();
        observeThread();
        try {
            if (!ready() || (port && !portReady())) {
                DISABLED_DECLINES.increment();
                return null;
            }
            // AlphaAdder changes decoded pixels. Prepared identity bytes are not equivalent.
            if ("TEXTURE_ALPHA_ADDER".equals(type)) {
                TYPE_DECLINES.increment();
                return null;
            }
            // ResourceHandle is lazy. Returning before BufferedInputStream/ImageIO therefore owns
            // no open stream. Refuse other InputStreams rather than risk leaking one on the return.
            if (resource == null || !RESOURCE_HANDLE.equals(resource.getClass().getName())) {
                RESOURCE_DECLINES.increment();
                return null;
            }
            PreparedTexture texture = TextureCompatibilityRuntime.lookup(logicalPath);
            if (texture == null) {
                LOOKUP_MISSES.increment();
                return null;
            }
            if (!(port ? supportedPort(texture) : supported(texture))) {
                LAYOUT_DECLINES.increment();
                TextureCompatibilityRuntime.declined(
                        TextureCompatibilityRuntime.FallbackReason.UNSUPPORTED_TEXTURE);
                return null;
            }
            Object carrier = createCarrier(resource.getClass().getClassLoader(), texture, port);
            HITS.increment();
            HIT_BYTES.add(texture.pixelBytes());
            TextureCompatibilityRuntime.hit(texture.pixelBytes());
            return carrier;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            FAILURES.increment();
            TextureCompatibilityRuntime.internalFailure();
            TextureCompatibilityRuntime.declined(
                    TextureCompatibilityRuntime.FallbackReason.INTERNAL_ERROR);
            if (INTERNAL_ERRORS.incrementAndGet() >= MAX_INTERNAL_ERRORS) {
                CIRCUIT_BREAKER.set(true);
            }
            return null;
        } finally {
            TOTAL_NANOS.add(Math.max(0L, System.nanoTime() - started));
        }
    }

    static boolean supported(PreparedTexture texture) {
        if (texture == null
                || texture.transformation() != PreparedTexture.Transformation.IDENTITY
                || texture.originalWidth() != texture.uploadWidth()
                || texture.originalHeight() != texture.uploadHeight()) {
            return false;
        }
        try {
            long expected = Math.multiplyExact(
                    Math.multiplyExact((long) texture.uploadWidth(), texture.uploadHeight()),
                    texture.channels());
            return expected == texture.pixelBytes();
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    static Object createCarrier(ClassLoader loader, PreparedTexture texture) throws Exception {
        return createCarrier(loader, texture, false);
    }

    static boolean supportedPort(PreparedTexture texture) {
        if (!supported(texture)) return false;
        if (texture.hasAlpha()) {
            // 0.8.7 reuses an ARGB scanline without clearing alpha when a == 0.
            // Prepared metadata does not retain ImageIO's decoded image type, so decline
            // every image containing zero alpha rather than silently change that behavior.
            ByteBuffer pixels = texture.pixelsView();
            for (int offset = 3; offset < pixels.limit(); offset += 4) {
                if (pixels.get(offset) == 0) return false;
            }
        }
        return true;
    }

    static Object createCarrier(ClassLoader loader, PreparedTexture texture, boolean port) throws Exception {
        Shape current = shape;
        if (current == null || current.loader() != loader || current.port() != port) {
            synchronized (FastRenderingPreparedTextureRuntime.class) {
                current = shape;
                if (current == null || current.loader() != loader || current.port() != port) {
                    current = Shape.resolve(loader, port);
                    shape = current;
                }
            }
        }
        Object carrier = current.constructor().newInstance();
        current.buffer().set(carrier, texture.pixelsView());
        current.width().setInt(carrier, texture.uploadWidth());
        current.height().setInt(carrier, texture.uploadHeight());
        current.hasAlpha().setBoolean(carrier, texture.hasAlpha());
        if (port) {
            current.imageWidth().setInt(carrier, texture.originalWidth());
            current.imageHeight().setInt(carrier, texture.originalHeight());
            current.isDds().set(carrier, null);
        } else {
            current.isDds().setBoolean(carrier, false);
        }
        current.mean().set(carrier, color(texture.color0Rgba()));
        current.weighted().set(carrier, color(texture.color1Rgba()));
        current.median().set(carrier, color(texture.color2Rgba()));
        return carrier;
    }

    static synchronized void installed() {
        installedTargets++;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("portPlanId", PORT_PLAN_ID);
        values.put("portRequested", Boolean.getBoolean(PORT_PROPERTY));
        values.put("property", ENABLED_PROPERTY);
        values.put("requested", requested());
        values.put("ready", ready());
        values.put("installedTargets", installedTargets);
        values.put("attempts", ATTEMPTS.sum());
        values.put("hits", HITS.sum());
        values.put("hitBytes", HIT_BYTES.sum());
        values.put("lookupMisses", LOOKUP_MISSES.sum());
        values.put("disabledDeclines", DISABLED_DECLINES.sum());
        values.put("typeDeclines", TYPE_DECLINES.sum());
        values.put("resourceDeclines", RESOURCE_DECLINES.sum());
        values.put("layoutDeclines", LAYOUT_DECLINES.sum());
        values.put("failures", FAILURES.sum());
        values.put("internalErrors", INTERNAL_ERRORS.get());
        values.put("circuitBreaker", CIRCUIT_BREAKER.get());
        values.put("totalNanos", TOTAL_NANOS.sum());
        synchronized (THREAD_LOCK) {
            values.put("threadCount", THREADS.size());
            values.put("threads", List.copyOf(new ArrayList<>(THREADS.keySet())));
        }
        return Map.copyOf(values);
    }

    private static Color color(int rgba) {
        return new Color(
                PreparedTexture.red(rgba),
                PreparedTexture.green(rgba),
                PreparedTexture.blue(rgba),
                PreparedTexture.alpha(rgba));
    }

    private static void observeThread() {
        long session = SESSION.get();
        if (OBSERVED_SESSION.get() == session) {
            return;
        }
        OBSERVED_SESSION.set(session);
        Thread thread = Thread.currentThread();
        String identity = thread.getName() + "#" + thread.getId();
        synchronized (THREAD_LOCK) {
            if (THREADS.containsKey(identity) || THREADS.size() >= MAX_THREAD_IDENTITIES) {
                return;
            }
            THREADS.put(identity, thread.getId());
        }
    }

    private record Shape(
            ClassLoader loader,
            boolean port,
            Constructor<?> constructor,
            Field buffer,
            Field width,
            Field height,
            Field hasAlpha,
            Field isDds,
            Field imageWidth,
            Field imageHeight,
            Field mean,
            Field weighted,
            Field median) {
        static Shape resolve(ClassLoader loader, boolean port) throws Exception {
            String name = port ? PORT_TEXTURE_DATA : TEXTURE_DATA;
            Class<?> type = Class.forName(name, false, loader);
            if (!name.equals(type.getName())) {
                throw new ClassNotFoundException(name);
            }
            Constructor<?> constructor = type.getConstructor();
            Field buffer = exactField(type, "buffer", ByteBuffer.class);
            Field width = exactField(type, "width", int.class);
            Field height = exactField(type, "height", int.class);
            Field hasAlpha = exactField(type, "hasAlpha", boolean.class);
            Field isDds = port ? exactField(type, "ddsImagePath", java.nio.file.Path.class)
                    : exactField(type, "isDDS", boolean.class);
            Field imageWidth = port ? exactField(type, "imageWidth", int.class) : null;
            Field imageHeight = port ? exactField(type, "imageHeight", int.class) : null;
            Field mean = exactField(type, "mean", Color.class);
            Field weighted = exactField(type, "weighted", Color.class);
            Field median = exactField(type, "median", Color.class);
            return new Shape(loader, port, constructor, buffer, width, height, hasAlpha, isDds,
                    imageWidth, imageHeight, mean, weighted, median);
        }

        private static Field exactField(Class<?> owner, String name, Class<?> expected)
                throws NoSuchFieldException {
            Field field = owner.getField(name);
            if (field.getType() != expected) {
                throw new NoSuchFieldException(name + " has type " + field.getType().getName());
            }
            return field;
        }
    }
}
