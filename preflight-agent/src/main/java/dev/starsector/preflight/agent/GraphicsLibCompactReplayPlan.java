package dev.starsector.preflight.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Exact whole-class replacement for GraphicsLib 1.12.1's repeated normal-map traversal. */
final class GraphicsLibCompactReplayPlan {
    static final String PLAN_ID = "graphicslib-1.12.1-compact-autogen-replay-v1";
    static final String TARGET_CLASS = "org/dark/shaders/util/TextureData";
    static final String ORIGINAL_SHA256 =
            "6a4302bcacd2dd90f6637c815d1443ddfdb3d28ff59095d48c875358de4e8594";
    static final String REPLACEMENT_SHA256 =
            GraphicsLibLazyNormalPlan.OPTIMIZED_SHA256;

    private static final String RESOURCE =
            "/dev/starsector/preflight/agent/graphicslib-texture-data-1.12.1.class.b64";
    private static final int MAX_ENCODED_BYTES = 128 * 1024;
    private static final byte[] FORBIDDEN_NEST =
            "org/dark/shaders/util/TextureData$AutoGenRequest".getBytes(StandardCharsets.UTF_8);
    private static final AtomicLong APPLIED = new AtomicLong();

    private static volatile State state = State.disabled();

    private GraphicsLibCompactReplayPlan() {
    }

    static void beginSession() {
        APPLIED.set(0);
        GraphicsLibNormalCacheRuntime.beginSession();
        state = State.disabled();
    }

    static void configure(boolean enabled) {
        if (!enabled) {
            state = State.disabled();
            return;
        }
        try {
            byte[] replacement = GraphicsLibLazyNormalPlan.transform(loadReplacement());
            if (replacement == null
                    || !REPLACEMENT_SHA256.equals(ClassSignature.parse(replacement).sha256())) {
                throw new IOException("lazy normal-map optimization differs");
            }
            state = new State(true, replacement, "ready");
        } catch (IOException | RuntimeException error) {
            state = new State(true, null, "rejected:" + message(error));
        }
    }

    static boolean ready() {
        return state.replacement != null;
    }

    static String status() {
        return state.status;
    }

    static byte[] transform(ClassSignature original) {
        State current = state;
        if (current.replacement == null
                || !TARGET_CLASS.equals(original.internalName())
                || !ORIGINAL_SHA256.equals(original.sha256())
                || original.majorVersion() != 61) {
            return null;
        }
        APPLIED.incrementAndGet();
        byte[] replacement = current.replacement.clone();
        if (StartupPhaseRuntime.phaseProbeEnabled()) {
            try {
                byte[] probed = StartupGraphicsTextureBreakdownPlan.transform(replacement);
                if (probed != null) {
                    return probed;
                }
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Attribution is optional; the reviewed compact replacement remains valid.
            }
        }
        return replacement;
    }

    static Map<String, Object> telemetry() {
        State current = state;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("enabled", current.enabled);
        values.put("status", current.status);
        values.put("targetClassSha256", ORIGINAL_SHA256);
        values.put("replacementClassSha256", REPLACEMENT_SHA256);
        values.put("applications", APPLIED.get());
        values.put("lazyNormalCache", GraphicsLibNormalCacheRuntime.telemetry());
        return values;
    }

    private static byte[] loadReplacement() throws IOException {
        byte[] encoded;
        try (InputStream input = GraphicsLibCompactReplayPlan.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("embedded replacement is missing");
            }
            encoded = input.readNBytes(MAX_ENCODED_BYTES + 1);
            if (encoded.length > MAX_ENCODED_BYTES) {
                throw new IOException("embedded replacement exceeds " + MAX_ENCODED_BYTES + " bytes");
            }
        }
        byte[] replacement = Base64.getMimeDecoder().decode(encoded);
        ClassSignature signature = ClassSignature.parse(replacement);
        if (!TARGET_CLASS.equals(signature.internalName())) {
            throw new IOException("embedded replacement class name differs");
        }
        if (!GraphicsLibLazyNormalPlan.BASE_SHA256.equals(signature.sha256())) {
            throw new IOException("embedded replacement SHA-256 differs");
        }
        if (signature.majorVersion() != 61) {
            throw new IOException("embedded replacement is not a Java 17 class");
        }
        if (contains(replacement, FORBIDDEN_NEST)) {
            throw new IOException("embedded replacement requires an unshipped nested class");
        }
        return replacement;
    }

    private static boolean contains(byte[] bytes, byte[] needle) {
        outer:
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    private record State(boolean enabled, byte[] replacement, String status) {
        private State {
            replacement = replacement == null ? null : Arrays.copyOf(replacement, replacement.length);
        }

        static State disabled() {
            return new State(false, null, "disabled");
        }
    }
}
