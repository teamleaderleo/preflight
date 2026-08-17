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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically writes in-flight elapsed duration so interrupted or force-killed game sessions
 * remain recoverable in the launch ledger and playtime totals.
 */
final class LaunchHeartbeat implements AutoCloseable {
    static final String FORMAT = "starsector-preflight-run-heartbeat-v1";
    static final String FILE_NAME = "heartbeat.json";
    static final long HEARTBEAT_INTERVAL_SECONDS = 30;

    private final Path runDirectory;
    private final Instant started;
    private final long startedNanos;
    private final String profileFingerprint;
    private final ScheduledExecutorService scheduler;

    private LaunchHeartbeat(Path runDirectory, Instant started, long startedNanos, String profileFingerprint) {
        this.runDirectory = runDirectory;
        this.started = started;
        this.startedNanos = startedNanos;
        this.profileFingerprint = profileFingerprint;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "preflight-launch-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    static LaunchHeartbeat start(Path runDirectory, Instant started, long startedNanos, String profileFingerprint) {
        LaunchHeartbeat heartbeat = new LaunchHeartbeat(runDirectory, started, startedNanos, profileFingerprint);
        heartbeat.write();
        heartbeat.scheduler.scheduleAtFixedRate(
                heartbeat::write,
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        return heartbeat;
    }

    void write() {
        try {
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            Instant now = Instant.now();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("format", FORMAT);
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
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> values = StrictJson.object(content);
            if (!FORMAT.equals(values.get("format"))) {
                return null;
            }
            Instant started = values.get("started") instanceof String s ? Instant.parse(s) : null;
            Instant lastHeartbeat = values.get("lastHeartbeat") instanceof String s ? Instant.parse(s) : null;
            Long elapsedMillis = values.get("elapsedMillis") instanceof Number n ? n.longValue() : null;
            String profile = values.get("profileFingerprint") instanceof String s ? s : null;
            if (started == null || elapsedMillis == null || elapsedMillis < 0) {
                return null;
            }
            return new Record(started, lastHeartbeat, elapsedMillis, profile);
        } catch (Exception unreadable) {
            return null;
        }
    }

    record Record(Instant started, Instant lastHeartbeat, long elapsedMillis, String profileFingerprint) {
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            Files.deleteIfExists(runDirectory.resolve(FILE_NAME));
            Files.deleteIfExists(runDirectory.resolve("." + FILE_NAME + ".tmp"));
        } catch (IOException ignored) {
        }
    }
}
