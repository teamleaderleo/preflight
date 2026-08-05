package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Hashes;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Exact-content, same-JVM memo for vanilla's expensive main-menu save-compatibility check. */
public final class SaveDescriptorCompatibilityRuntime {
    static final String PLAN_ID = "save-descriptor-compatibility-v1";
    private static final String FORMAT = "preflight-save-descriptor-compatibility-v1";
    private static final List<String> CANDIDATES = List.of(
            "descriptor.xml", "descriptor.xml.bak", "descriptor.xml.inprogress");

    private static final AtomicLong LOOKUPS = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();
    private static final AtomicLong RECORDS = new AtomicLong();
    private static final AtomicLong HASHED_BYTES = new AtomicLong();
    private static final AtomicLong FINGERPRINT_FAILURES = new AtomicLong();
    private static final Map<String, Boolean> results = new LinkedHashMap<>();
    private static boolean installed;

    private SaveDescriptorCompatibilityRuntime() {
    }

    static synchronized void beginSession() {
        LOOKUPS.set(0L);
        HITS.set(0L);
        MISSES.set(0L);
        RECORDS.set(0L);
        HASHED_BYTES.set(0L);
        FINGERPRINT_FAILURES.set(0L);
        results.clear();
        installed = false;
    }

    static void installed() {
        installed = true;
    }

    /** Returns vanilla's prior answer only after vanilla computed it in this same JVM. */
    public static synchronized Boolean lookup(File saveDirectory) {
        LOOKUPS.incrementAndGet();
        if (saveDirectory == null) {
            MISSES.incrementAndGet();
            return null;
        }
        try {
            Boolean result = results.get(fingerprint(saveDirectory.toPath()));
            if (result == null) {
                MISSES.incrementAndGet();
            } else {
                HITS.incrementAndGet();
            }
            return result;
        } catch (IOException | RuntimeException ignored) {
            FINGERPRINT_FAILURES.incrementAndGet();
            MISSES.incrementAndGet();
            return null;
        }
    }

    /** Records the exact result vanilla just computed and leaves that result on the operand stack. */
    public static synchronized boolean record(boolean result, File saveDirectory) {
        if (saveDirectory == null) return result;
        try {
            results.put(fingerprint(saveDirectory.toPath()), result);
            RECORDS.incrementAndGet();
        } catch (IOException | RuntimeException ignored) {
            FINGERPRINT_FAILURES.incrementAndGet();
        }
        return result;
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installed", installed);
        values.put("entries", results.size());
        values.put("lookups", LOOKUPS.get());
        values.put("hits", HITS.get());
        values.put("misses", MISSES.get());
        values.put("records", RECORDS.get());
        values.put("hashedBytes", HASHED_BYTES.get());
        values.put("fingerprintFailures", FINGERPRINT_FAILURES.get());
        return values;
    }

    private static String fingerprint(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) throw new IOException("save directory is unavailable");
        StringBuilder canonical = new StringBuilder(FORMAT).append('\n');
        for (String name : CANDIDATES) {
            Path candidate = directory.resolve(name);
            canonical.append(name).append('=');
            if (!Files.exists(candidate)) {
                canonical.append("missing\n");
                continue;
            }
            if (Files.isDirectory(candidate)) {
                canonical.append("directory\n");
                continue;
            }
            if (!Files.isRegularFile(candidate)) {
                throw new IOException("descriptor candidate is not a regular file");
            }
            BasicFileAttributes before = Files.readAttributes(candidate, BasicFileAttributes.class);
            String sha256 = Hashes.sha256(candidate);
            BasicFileAttributes after = Files.readAttributes(candidate, BasicFileAttributes.class);
            if (before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
                throw new IOException("descriptor changed while hashing");
            }
            HASHED_BYTES.addAndGet(after.size());
            canonical.append("file:").append(after.size()).append(':').append(sha256).append('\n');
        }
        return Hashes.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }
}
