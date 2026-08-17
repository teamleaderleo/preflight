package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically writes in-flight elapsed duration so interrupted or force-killed game sessions
 * remain recoverable in the launch ledger and playtime totals.
 */
final class LaunchHeartbeat implements AutoCloseable {
    static final String FORMAT = "starsector-preflight-run-heartbeat-v2";
    static final String FILE_NAME = "heartbeat.json";
    static final long HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final long MAX_BYTES = 64 * 1024;
    private static final long MAX_ELAPSED_MILLIS = Duration.ofDays(365).toMillis();
    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "format", "launchId", "ownerPid", "ownerStartedAt", "started", "lastHeartbeat", "elapsedMillis");
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "format", "launchId", "ownerPid", "ownerStartedAt", "started", "lastHeartbeat",
            "elapsedMillis", "profileFingerprint");

    private final Path runDirectory;
    private final String launchId;
    private final long ownerPid;
    private final Instant ownerStartedAt;
    private final Instant started;
    private final long startedNanos;
    private final String profileFingerprint;
    private final ScheduledExecutorService scheduler;

    private LaunchHeartbeat(
            Path runDirectory,
            String launchId,
            Instant started,
            long startedNanos,
            String profileFingerprint) {
        this.runDirectory = runDirectory;
        this.launchId = requireLaunchId(launchId);
        ProcessHandle owner = ProcessHandle.current();
        this.ownerPid = owner.pid();
        this.ownerStartedAt = owner.info().startInstant().orElse(null);
        this.started = started;
        this.startedNanos = startedNanos;
        this.profileFingerprint = profileFingerprint;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "preflight-launch-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    static LaunchHeartbeat start(
            Path runDirectory,
            String launchId,
            Instant started,
            long startedNanos,
            String profileFingerprint) {
        LaunchHeartbeat heartbeat = new LaunchHeartbeat(
                runDirectory, launchId, started, startedNanos, profileFingerprint);
        // A heartbeat is recovery bookkeeping. A platform that cannot expose the wrapper's exact
        // process-start identity loses recovery for this run; it must never lose the launch.
        if (heartbeat.ownerStartedAt == null) return heartbeat;
        heartbeat.write();
        heartbeat.scheduler.scheduleAtFixedRate(
                heartbeat::write,
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        return heartbeat;
    }

    synchronized void write() {
        if (ownerStartedAt == null) return;
        try {
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            Instant now = Instant.now();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("format", FORMAT);
            values.put("launchId", launchId);
            values.put("ownerPid", ownerPid);
            values.put("ownerStartedAt", ownerStartedAt);
            values.put("started", started.toString());
            values.put("lastHeartbeat", now.toString());
            values.put("elapsedMillis", elapsedMillis);
            if (profileFingerprint != null) {
                values.put("profileFingerprint", profileFingerprint);
            }
            Path target = runDirectory.resolve(FILE_NAME);
            Path temporary = runDirectory.resolve("." + FILE_NAME + ".tmp");
            Files.writeString(temporary, Json.object(values), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ignored) {
            // Heartbeat failure must never interrupt the game.
        }
    }

    static Record read(Path runDirectory) {
        Path file = runDirectory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            if (Files.size(file) > MAX_BYTES) return null;
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> values = StrictJson.object(content);
            if (!FORMAT.equals(values.get("format"))) {
                return null;
            }
            if (!ALLOWED_FIELDS.containsAll(values.keySet())
                    || !values.keySet().containsAll(REQUIRED_FIELDS)) return null;
            String launchId = values.get("launchId") instanceof String value
                    ? requireLaunchId(value) : null;
            Long ownerPid = integer(values.get("ownerPid"), 1L, Long.MAX_VALUE);
            Instant ownerStartedAt = instant(values.get("ownerStartedAt"));
            Instant started = instant(values.get("started"));
            Instant lastHeartbeat = instant(values.get("lastHeartbeat"));
            Long elapsedMillis = integer(values.get("elapsedMillis"), 0L, MAX_ELAPSED_MILLIS);
            String profile = values.get("profileFingerprint") instanceof String s ? s : null;
            if (launchId == null
                    || ownerPid == null
                    || ownerStartedAt == null
                    || started == null
                    || lastHeartbeat == null
                    || elapsedMillis == null
                    || (profile != null && (profile.isBlank() || profile.length() > 256))) {
                return null;
            }
            return new Record(
                    launchId, ownerPid, ownerStartedAt, started, lastHeartbeat, elapsedMillis, profile);
        } catch (Exception unreadable) {
            return null;
        }
    }

    static void complete(Path runDirectory, String launchId) {
        Record record = read(runDirectory);
        if (record == null || !record.launchId().equals(launchId)) return;
        try {
            Files.deleteIfExists(runDirectory.resolve(FILE_NAME));
            Files.deleteIfExists(runDirectory.resolve("." + FILE_NAME + ".tmp"));
        } catch (IOException ignored) {
        }
    }

    private static String requireLaunchId(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException("Launch id is not canonical");
        }
        return value;
    }

    private static Instant instant(Object value) {
        return value instanceof String text ? Instant.parse(text) : null;
    }

    private static Long integer(Object value, long minimum, long maximum) {
        if (!(value instanceof Number number)
                || number.doubleValue() != number.longValue()
                || number.longValue() < minimum
                || number.longValue() > maximum) return null;
        return number.longValue();
    }

    record Record(
            String launchId,
            long ownerPid,
            Instant ownerStartedAt,
            Instant started,
            Instant lastHeartbeat,
            long elapsedMillis,
            String profileFingerprint) {
        boolean ownerAlive() {
            ProcessHandle process = ProcessHandle.of(ownerPid).orElse(null);
            return process != null
                    && process.isAlive()
                    && process.info().startInstant().filter(ownerStartedAt::equals).isPresent();
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        write();
    }
}
