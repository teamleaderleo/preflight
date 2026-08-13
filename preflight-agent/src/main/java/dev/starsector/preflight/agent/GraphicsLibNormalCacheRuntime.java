package dev.starsector.preflight.agent;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
 * Validates GraphicsLib's generated PNG cache without decoding or uploading its textures.
 *
 * <p>The returned marker implements SpriteAPI at runtime, but carries no texture. The exact
 * GraphicsLib transform immediately converts it into GraphicsLib's existing unloaded-entry state.
 * Complete validations are journaled with filesystem identity and metadata; changed or unknown
 * files always return to complete structural and CRC validation.
 */
public final class GraphicsLibNormalCacheRuntime {
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final int MAX_CHUNK_BYTES = 128 * 1024 * 1024;
    private static final int BUFFER_BYTES = 32 * 1024;
    private static final int IHDR = 0x49484452;
    private static final int IDAT = 0x49444154;
    private static final int IEND = 0x49454E44;
    private static final String SPRITE_API = "com.fs.starfarer.api.graphics.SpriteAPI";
    private static final String GRAPHICSLIB_ID = "shaderLib";
    private static final int JOURNAL_MAGIC = 0x50464E4A;
    private static final int JOURNAL_VERSION = 1;
    private static final int MAX_JOURNAL_ENTRIES = 100_000;
    private static final String JOURNAL_NAME = "graphicslib-normal-validation-v1.bin";

    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong FALLBACKS = new AtomicLong();
    private static final AtomicLong VALIDATED_BYTES = new AtomicLong();
    private static final AtomicLong VALIDATION_NANOS = new AtomicLong();
    private static final AtomicLong ROOT_FAILURES = new AtomicLong();
    private static final AtomicLong JOURNAL_HITS = new AtomicLong();
    private static final AtomicLong JOURNAL_MISSES = new AtomicLong();
    private static final AtomicLong JOURNAL_LOAD_FAILURES = new AtomicLong();
    private static final AtomicLong JOURNAL_WRITE_FAILURES = new AtomicLong();
    private static final AtomicLong METADATA_PROBES = new AtomicLong();
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean();
    private static final ThreadLocal<byte[]> READ_BUFFER =
            ThreadLocal.withInitial(() -> new byte[BUFFER_BYTES]);
    private static final Map<String, FileStamp> verified = new ConcurrentHashMap<>();

    private static volatile Path cacheRoot;
    private static volatile Path journalFile;
    private static volatile Path journalRoot;
    private static volatile boolean journalLoaded;
    private static volatile boolean journalDirty;
    private static volatile boolean rootUnavailable;
    private static volatile Object lazyMarker;
    private static volatile Path testCacheRoot;
    private static volatile Class<?> testSpriteApi;

    private GraphicsLibNormalCacheRuntime() {
    }

    static void beginSession() {
        CALLS.set(0);
        HITS.set(0);
        FALLBACKS.set(0);
        VALIDATED_BYTES.set(0);
        VALIDATION_NANOS.set(0);
        ROOT_FAILURES.set(0);
        JOURNAL_HITS.set(0);
        JOURNAL_MISSES.set(0);
        JOURNAL_LOAD_FAILURES.set(0);
        JOURNAL_WRITE_FAILURES.set(0);
        METADATA_PROBES.set(0);
        cacheRoot = null;
        journalFile = null;
        journalRoot = null;
        journalLoaded = false;
        journalDirty = false;
        verified.clear();
        rootUnavailable = false;
        lazyMarker = null;
    }

    static void configure(Path cacheDirectory) {
        if (cacheDirectory == null) {
            return;
        }
        journalFile = cacheDirectory.toAbsolutePath().normalize().resolve(JOURNAL_NAME);
        if (SHUTDOWN_HOOK_INSTALLED.compareAndSet(false, true)) {
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(
                        GraphicsLibNormalCacheRuntime::flushJournal,
                        "preflight-graphicslib-normal-journal"));
            } catch (IllegalStateException | SecurityException ignored) {
                // The journal is optional; validation remains complete without it.
            }
        }
    }

    /** Returns a marker SpriteAPI for a fully validated cache hit, otherwise {@code null}. */
    public static Object lazySprite(String resourcePath) {
        CALLS.incrementAndGet();
        long started = System.nanoTime();
        try {
            Path root = resolveCacheRoot();
            Path png = resolveCacheFile(root, resourcePath);
            ensureJournalLoaded(root);
            String name = png.getFileName().toString();
            FileStamp before = FileStamp.capture(png);
            if (before.equals(verified.get(name))) {
                JOURNAL_HITS.incrementAndGet();
            } else {
                JOURNAL_MISSES.incrementAndGet();
                verified.remove(name);
                long bytes = validatePng(png);
                FileStamp after = FileStamp.capture(png);
                if (!before.equals(after)) {
                    throw new IOException("generated normal changed during validation");
                }
                verified.put(name, after);
                journalDirty = true;
                VALIDATED_BYTES.addAndGet(bytes);
            }
            Object marker = resolveMarker();
            HITS.incrementAndGet();
            return marker;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            FALLBACKS.incrementAndGet();
            return null;
        } finally {
            VALIDATION_NANOS.addAndGet(System.nanoTime() - started);
        }
    }

    public static boolean isLazySprite(Object sprite) {
        return sprite != null
                && Proxy.isProxyClass(sprite.getClass())
                && Proxy.getInvocationHandler(sprite) instanceof LazySpriteHandler;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("calls", CALLS.get());
        values.put("hits", HITS.get());
        values.put("fallbacks", FALLBACKS.get());
        values.put("validatedBytes", VALIDATED_BYTES.get());
        values.put("validationMillis", VALIDATION_NANOS.get() / 1_000_000L);
        values.put("rootFailures", ROOT_FAILURES.get());
        values.put("journalHits", JOURNAL_HITS.get());
        values.put("journalMisses", JOURNAL_MISSES.get());
        values.put("journalEntries", verified.size());
        values.put("journalLoadFailures", JOURNAL_LOAD_FAILURES.get());
        values.put("journalWriteFailures", JOURNAL_WRITE_FAILURES.get());
        values.put("metadataProbes", METADATA_PROBES.get());
        return values;
    }

    private static void ensureJournalLoaded(Path root) {
        if (journalLoaded) {
            return;
        }
        synchronized (GraphicsLibNormalCacheRuntime.class) {
            if (journalLoaded) {
                return;
            }
            journalRoot = root.toAbsolutePath().normalize();
            Path source = journalFile;
            if (source != null && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                        Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)))) {
                    if (input.readInt() != JOURNAL_MAGIC || input.readInt() != JOURNAL_VERSION
                            || !journalRoot.toString().equals(input.readUTF())) {
                        throw new IOException("GraphicsLib normal journal identity differs");
                    }
                    int count = input.readInt();
                    if (count < 0 || count > MAX_JOURNAL_ENTRIES) {
                        throw new IOException("GraphicsLib normal journal count is invalid");
                    }
                    for (int index = 0; index < count; index++) {
                        String name = input.readUTF();
                        if (!validCacheName(name) || verified.putIfAbsent(name,
                                new FileStamp(input.readLong(), input.readLong(), input.readUTF()))
                                != null) {
                            throw new IOException("GraphicsLib normal journal entry is invalid");
                        }
                    }
                    if (input.read() != -1) {
                        throw new IOException("GraphicsLib normal journal has trailing data");
                    }
                } catch (IOException | RuntimeException ignored) {
                    verified.clear();
                    JOURNAL_LOAD_FAILURES.incrementAndGet();
                }
            }
            journalLoaded = true;
        }
    }

    private static boolean validCacheName(String name) {
        return name != null && !name.isBlank() && name.length() <= 1024
                && name.endsWith("_normal.png")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    private static void flushJournal() {
        Path destination = journalFile;
        Path root = journalRoot;
        if (!journalDirty || destination == null || root == null || verified.isEmpty()) {
            return;
        }
        synchronized (GraphicsLibNormalCacheRuntime.class) {
            if (!journalDirty) {
                return;
            }
            Path temporary = null;
            try {
                Files.createDirectories(destination.getParent());
                Map<String, FileStamp> snapshot = new TreeMap<>(verified);
                temporary = Files.createTempFile(destination.getParent(),
                        ".graphicslib-normal-validation-", ".tmp");
                try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                        Files.newOutputStream(temporary)))) {
                    output.writeInt(JOURNAL_MAGIC);
                    output.writeInt(JOURNAL_VERSION);
                    output.writeUTF(root.toString());
                    output.writeInt(snapshot.size());
                    for (Map.Entry<String, FileStamp> entry : snapshot.entrySet()) {
                        output.writeUTF(entry.getKey());
                        output.writeLong(entry.getValue().size());
                        output.writeLong(entry.getValue().modifiedNanos());
                        output.writeUTF(entry.getValue().fileKey());
                    }
                }
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException unsupported) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                journalDirty = false;
            } catch (IOException | RuntimeException ignored) {
                JOURNAL_WRITE_FAILURES.incrementAndGet();
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException alsoIgnored) {
                        // Best-effort cleanup of an optional journal temporary.
                    }
                }
            }
        }
    }

    private static Path resolveCacheRoot() throws ReflectiveOperationException, IOException {
        Path override = testCacheRoot;
        if (override != null) {
            return override;
        }
        Path current = cacheRoot;
        if (current != null) {
            return current;
        }
        if (rootUnavailable) {
            throw new IOException("GraphicsLib cache root is unavailable");
        }
        synchronized (GraphicsLibNormalCacheRuntime.class) {
            if (cacheRoot != null) {
                return cacheRoot;
            }
            if (rootUnavailable) {
                throw new IOException("GraphicsLib cache root is unavailable");
            }
            try {
                ClassLoader loader = GraphicsLibNormalCacheRuntime.class.getClassLoader();
                Class<?> global = Class.forName("com.fs.starfarer.api.Global", false, loader);
                Object settings = global.getMethod("getSettings").invoke(null);
                Class<?> settingsApi = Class.forName(
                        "com.fs.starfarer.api.SettingsAPI", false, loader);
                Object modManager = settingsApi.getMethod("getModManager").invoke(settings);
                Class<?> modManagerApi = Class.forName(
                        "com.fs.starfarer.api.ModManagerAPI", false, loader);
                Object modSpec = modManagerApi
                        .getMethod("getModSpec", String.class)
                        .invoke(modManager, GRAPHICSLIB_ID);
                if (modSpec == null) {
                    throw new IOException("GraphicsLib mod spec is unavailable");
                }
                Class<?> modSpecApi = Class.forName(
                        "com.fs.starfarer.api.ModSpecAPI", false, loader);
                String modPath = (String) modSpecApi.getMethod("getPath").invoke(modSpec);
                Path resolved = Path.of(modPath).toRealPath().resolve("cache").normalize();
                if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("GraphicsLib cache directory is unavailable");
                }
                cacheRoot = resolved;
                return resolved;
            } catch (ReflectiveOperationException | IOException | RuntimeException error) {
                rootUnavailable = true;
                ROOT_FAILURES.incrementAndGet();
                throw error;
            }
        }
    }

    private static Path resolveCacheFile(Path root, String resourcePath) throws IOException {
        if (resourcePath == null || !resourcePath.startsWith("cache/")) {
            throw new IOException("invalid GraphicsLib cache path");
        }
        String name = resourcePath.substring("cache/".length());
        if (!validCacheName(name)) {
            throw new IOException("path is outside GraphicsLib's generated-normal cache");
        }
        // FileStamp.capture() is the authoritative regular-file and symlink check. Avoiding a
        // preceding Files.isRegularFile() halves metadata syscalls on every warm journal hit.
        return root.resolve(name);
    }

    /** Returns the number of bytes consumed when the file is a complete, CRC-valid PNG. */
    private static long validatePng(Path png) throws IOException {
        long fileSize = Files.size(png);
        if (fileSize < 57 || fileSize > Integer.MAX_VALUE) {
            throw new IOException("implausible generated-normal PNG size");
        }
        long consumed = 0;
        boolean sawHeader = false;
        boolean sawImageData = false;
        boolean sawEnd = false;
        byte[] buffer = READ_BUFFER.get();
        byte[] type = new byte[4];
        try (InputStream raw = Files.newInputStream(png, LinkOption.NOFOLLOW_LINKS);
                DataInputStream input = new DataInputStream(raw)) {
            byte[] signature = input.readNBytes(PNG_SIGNATURE.length);
            consumed += signature.length;
            if (!java.util.Arrays.equals(PNG_SIGNATURE, signature)) {
                throw new IOException("invalid PNG signature");
            }
            while (!sawEnd) {
                int length;
                try {
                    length = input.readInt();
                } catch (EOFException error) {
                    throw new IOException("PNG ended before IEND", error);
                }
                consumed += 4;
                if (length < 0 || length > MAX_CHUNK_BYTES || consumed + 8L + length > fileSize) {
                    throw new IOException("invalid PNG chunk length");
                }
                input.readFully(type);
                consumed += 4;
                int chunk = (Byte.toUnsignedInt(type[0]) << 24)
                        | (Byte.toUnsignedInt(type[1]) << 16)
                        | (Byte.toUnsignedInt(type[2]) << 8)
                        | Byte.toUnsignedInt(type[3]);
                if (!sawHeader && (chunk != IHDR || length != 13)) {
                    throw new IOException("PNG does not begin with IHDR");
                }
                CRC32 crc = new CRC32();
                crc.update(type);
                int remaining = length;
                int offset = 0;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
                    if (count < 0) {
                        throw new EOFException("PNG chunk is truncated");
                    }
                    crc.update(buffer, 0, count);
                    if (chunk == IHDR && offset == 0) {
                        validateHeader(buffer, count);
                    }
                    offset += count;
                    remaining -= count;
                    consumed += count;
                }
                long expected = Integer.toUnsignedLong(input.readInt());
                consumed += 4;
                if (crc.getValue() != expected) {
                    throw new IOException("PNG chunk CRC mismatch");
                }
                if (chunk == IHDR) {
                    if (sawHeader) {
                        throw new IOException("duplicate PNG header");
                    }
                    sawHeader = true;
                } else if (chunk == IDAT) {
                    sawImageData = true;
                } else if (chunk == IEND) {
                    if (length != 0) {
                        throw new IOException("invalid PNG end chunk");
                    }
                    sawEnd = true;
                }
            }
            if (!sawHeader || !sawImageData || input.read() != -1 || consumed != fileSize) {
                throw new IOException("incomplete or trailing PNG data");
            }
        }
        return consumed;
    }

    private static Class<?> resolveSpriteApi() throws ClassNotFoundException {
        Class<?> override = testSpriteApi;
        return override != null
                ? override
                : Class.forName(SPRITE_API, false, GraphicsLibNormalCacheRuntime.class.getClassLoader());
    }

    private static Object resolveMarker() throws ClassNotFoundException {
        Object current = lazyMarker;
        if (current != null) {
            return current;
        }
        synchronized (GraphicsLibNormalCacheRuntime.class) {
            if (lazyMarker == null) {
                Class<?> spriteApi = resolveSpriteApi();
                lazyMarker = Proxy.newProxyInstance(spriteApi.getClassLoader(),
                        new Class<?>[] {spriteApi}, new LazySpriteHandler());
            }
            return lazyMarker;
        }
    }

    private static void validateHeader(byte[] header, int count) throws IOException {
        if (count != 13) {
            throw new IOException("truncated PNG header");
        }
        int width = readPositiveInt(header, 0);
        int height = readPositiveInt(header, 4);
        int bitDepth = Byte.toUnsignedInt(header[8]);
        int colorType = Byte.toUnsignedInt(header[9]);
        boolean legalDepth = switch (colorType) {
            case 0 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4
                    || bitDepth == 8 || bitDepth == 16;
            case 2, 4, 6 -> bitDepth == 8 || bitDepth == 16;
            case 3 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
            default -> false;
        };
        if (width <= 0 || height <= 0 || !legalDepth
                || header[10] != 0 || header[11] != 0
                || (header[12] != 0 && header[12] != 1)) {
            throw new IOException("invalid PNG header fields");
        }
    }

    private static int readPositiveInt(byte[] bytes, int offset) {
        return (Byte.toUnsignedInt(bytes[offset]) << 24)
                | (Byte.toUnsignedInt(bytes[offset + 1]) << 16)
                | (Byte.toUnsignedInt(bytes[offset + 2]) << 8)
                | Byte.toUnsignedInt(bytes[offset + 3]);
    }

    static void configureForTest(Path root, Class<?> spriteApi) throws IOException {
        testCacheRoot = root.toRealPath();
        testSpriteApi = spriteApi;
        beginSession();
    }

    static void configureForTest(Path root, Path persistentCache, Class<?> spriteApi)
            throws IOException {
        configureForTest(root, spriteApi);
        configure(persistentCache);
    }

    static void flushJournalForTest() {
        flushJournal();
    }

    static void clearTestConfiguration() {
        testCacheRoot = null;
        testSpriteApi = null;
        beginSession();
    }

    private static final class LazySpriteHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getHeight", "getWidth", "getTextureHeight", "getTextureWidth" -> 1.0f;
                case "getTextureId" -> 0;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                case "toString" -> "Preflight lazy GraphicsLib normal-map marker";
                default -> defaultValue(method.getReturnType());
            };
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive() || type == void.class) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == char.class) {
                return '\0';
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0.0f;
            }
            return 0.0d;
        }
    }

    private record FileStamp(long size, long modifiedNanos, String fileKey) {
        static FileStamp capture(Path path) throws IOException {
            METADATA_PROBES.incrementAndGet();
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IOException("generated normal is not a regular file");
            }
            Object key = attributes.fileKey();
            return new FileStamp(attributes.size(),
                    attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                    key == null ? "" : key.toString());
        }
    }
}
