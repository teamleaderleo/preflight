package dev.starsector.preflight.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Allocation-light equivalent of Starsector's shared UTF-8 text reader. */
public final class LoadingUtilsReaderRuntime {
    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong INPUT_BYTES = new AtomicLong();
    private static final AtomicLong REMOVED_CARRIAGE_RETURNS = new AtomicLong();
    private static final AtomicLong NANOS = new AtomicLong();

    private LoadingUtilsReaderRuntime() {
    }

    static void beginSession() {
        CALLS.set(0);
        INPUT_BYTES.set(0);
        REMOVED_CARRIAGE_RETURNS.set(0);
        NANOS.set(0);
    }

    public static String readUtf8(InputStream input) throws IOException {
        long started = System.nanoTime();
        try {
            byte[] bytes = input.readAllBytes();
            int output = 0;
            long removed = 0;
            for (byte value : bytes) {
                if (value == '\r') {
                    removed++;
                } else {
                    bytes[output++] = value;
                }
            }
            CALLS.incrementAndGet();
            INPUT_BYTES.addAndGet(bytes.length);
            REMOVED_CARRIAGE_RETURNS.addAndGet(removed);
            return new String(bytes, 0, output, StandardCharsets.UTF_8);
        } finally {
            try {
                input.close();
            } finally {
                NANOS.addAndGet(System.nanoTime() - started);
            }
        }
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("calls", CALLS.get());
        values.put("inputBytes", INPUT_BYTES.get());
        values.put("removedCarriageReturns", REMOVED_CARRIAGE_RETURNS.get());
        values.put("millis", TimeUnit.NANOSECONDS.toMillis(NANOS.get()));
        return Map.copyOf(values);
    }
}
