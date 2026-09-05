package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Hashes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Copies only already-decoded bytes; the installed Vorbis decoder and its refill stay intact. */
public final class WindowsPcmCopyRuntime {
    static final String PLAN_ID = "windows-pcm-buffer-copy-v1";
    public static final String PROPERTY = "preflight.audio.windowsPcmCopy";
    static final String STREAM_SHA = "d5f2b86bab84ec3a40945ebd488c20ab9401f590f2b4963f358cca4c98757754";
    private static final AtomicLong COMPLETED = new AtomicLong(), DECLINED = new AtomicLong();
    private static final AtomicLong BYTES = new AtomicLong(), READS = new AtomicLong();
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override protected Access computeValue(Class<?> type) {
            if (!type.getName().equals("sound.F")) return null;
            try (InputStream bytes = type.getResourceAsStream("F.class")) {
                if (bytes == null || !STREAM_SHA.equals(Hashes.sha256(bytes.readNBytes(1_048_577)))) return null;
                Field buffer = type.getDeclaredField("String.super");
                Field offset = type.getDeclaredField("void");
                Method end = type.getMethod("o00000");
                if (buffer.getType() != ByteBuffer.class || offset.getType() != int.class
                        || end.getReturnType() != boolean.class) return null;
                buffer.setAccessible(true);
                offset.setAccessible(true);
                return new Access(buffer, offset, end);
            } catch (IOException | ReflectiveOperationException | RuntimeException failure) {
                return null;
            }
        }
    };

    private WindowsPcmCopyRuntime() { }
    static boolean enabled() { return Boolean.getBoolean(PROPERTY); }
    static void beginSession() { COMPLETED.set(0); DECLINED.set(0); BYTES.set(0); READS.set(0); }
    static Map<String, Object> report() {
        return Map.of("enabled", enabled(), "completed", COMPLETED.get(), "declined", DECLINED.get(),
                "bulkBytes", BYTES.get(), "originalReadCalls", READS.get(), "chunkBytes", 8192);
    }

    /** False guarantees that no stream or output bytes were touched. */
    public static boolean copy(InputStream stream, ByteArrayOutputStream output) throws IOException {
        if (!enabled()) return false;
        Access access = stream == null ? null : ACCESS.get(stream.getClass());
        if (access == null) { DECLINED.incrementAndGet(); return false; }
        return drain(stream, output, new BufferedAccess() {
            public ByteBuffer buffer() throws ReflectiveOperationException { return (ByteBuffer) access.buffer.get(stream); }
            public int offset() throws ReflectiveOperationException { return access.offset.getInt(stream); }
            public void offset(int value) throws ReflectiveOperationException { access.offset.setInt(stream, value); }
            public boolean end() throws ReflectiveOperationException { return (boolean) access.end.invoke(stream); }
        });
    }

    interface BufferedAccess {
        ByteBuffer buffer() throws ReflectiveOperationException;
        int offset() throws ReflectiveOperationException;
        void offset(int value) throws ReflectiveOperationException;
        boolean end() throws ReflectiveOperationException;
    }

    static boolean drain(InputStream stream, ByteArrayOutputStream output, BufferedAccess access) throws IOException {
        byte[] chunk = new byte[8192];
        long copied = 0, reads = 0;
        try {
            while (!access.end()) {
                ByteBuffer buffer = access.buffer();
                int offset = access.offset();
                if (buffer != null && offset >= 0 && offset < buffer.position()) {
                    int count = Math.min(chunk.length, buffer.position() - offset);
                    // Absolute bulk get preserves position/limit: position is the producer's end,
                    // while the separate field is the consumer cursor used by stock read().
                    buffer.get(offset, chunk, 0, count);
                    output.write(chunk, 0, count);
                    access.offset(offset + count);
                    copied += count;
                } else {
                    // Preserve the original refill, exceptions and even its final write(-1):
                    // the stock loop checks end BEFORE read and can append that 0xff byte.
                    output.write(stream.read());
                    reads++;
                }
            }
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IOException(cause);
        } catch (ReflectiveOperationException failure) {
            throw new IOException("Reviewed PCM cursor access failed", failure);
        }
        COMPLETED.incrementAndGet();
        BYTES.addAndGet(copied);
        READS.addAndGet(reads);
        return true;
    }

    private record Access(Field buffer, Field offset, Method end) { }
}
