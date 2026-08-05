package dev.starsector.preflight.agent;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
 * Validates GraphicsLib's generated PNG cache without decoding or uploading its textures.
 *
 * <p>The returned marker implements SpriteAPI at runtime, but carries no texture. The exact
 * GraphicsLib transform immediately converts it into GraphicsLib's existing unloaded-entry state.
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

    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong FALLBACKS = new AtomicLong();
    private static final AtomicLong VALIDATED_BYTES = new AtomicLong();
    private static final AtomicLong VALIDATION_NANOS = new AtomicLong();
    private static final AtomicLong ROOT_FAILURES = new AtomicLong();
    private static final ThreadLocal<byte[]> READ_BUFFER =
            ThreadLocal.withInitial(() -> new byte[BUFFER_BYTES]);

    private static volatile Path cacheRoot;
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
        cacheRoot = null;
        rootUnavailable = false;
        lazyMarker = null;
    }

    /** Returns a marker SpriteAPI for a fully validated cache hit, otherwise {@code null}. */
    public static Object lazySprite(String resourcePath) {
        CALLS.incrementAndGet();
        long started = System.nanoTime();
        try {
            Path root = resolveCacheRoot();
            Path png = resolveCacheFile(root, resourcePath);
            long bytes = validatePng(png);
            VALIDATED_BYTES.addAndGet(bytes);
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
        return values;
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
        if (resourcePath == null || resourcePath.indexOf('\\') >= 0) {
            throw new IOException("invalid GraphicsLib cache path");
        }
        Path relative = Path.of(resourcePath).normalize();
        if (relative.isAbsolute()
                || relative.getNameCount() != 2
                || !"cache".equals(relative.getName(0).toString())
                || !relative.getFileName().toString().endsWith("_normal.png")) {
            throw new IOException("path is outside GraphicsLib's generated-normal cache");
        }
        Path png = root.resolve(relative.getFileName()).normalize();
        if (!png.getParent().equals(root)
                || !Files.isRegularFile(png, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("generated normal is missing or not a regular file");
        }
        return png;
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
}
