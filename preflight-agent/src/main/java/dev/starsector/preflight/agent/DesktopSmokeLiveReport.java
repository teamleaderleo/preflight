package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Low-frequency live evidence publisher enabled only for unattended desktop smoke runs. */
final class DesktopSmokeLiveReport implements AutoCloseable {
    static final String FRAME_FORMAT = FrameTimeTelemetry.FRAME_REPORT_FORMAT;
    static final String HEALTH_FORMAT = "starsector-preflight-runtime-adapter-health-v1";
    private static final long PUBLISH_INTERVAL_MILLIS = 1_000L;

    private final Path frameDestination;
    private final Path healthDestination;
    private final AdapterMode adapterMode;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread publisher;
    private volatile String writeProblem;

    private DesktopSmokeLiveReport(
            Path frameDestination, Path healthDestination, AdapterMode adapterMode, boolean enabled)
            throws IOException {
        this.frameDestination = frameDestination;
        this.healthDestination = healthDestination;
        this.adapterMode = adapterMode;
        if (!enabled) {
            publisher = null;
            return;
        }
        publish();
        publisher = new Thread(this::publishLoop, "Preflight-Desktop-Smoke-Evidence");
        publisher.setDaemon(true);
        publisher.start();
    }

    static DesktopSmokeLiveReport start(Path adapterReport, AdapterMode adapterMode)
            throws IOException {
        return start(adapterReport, adapterMode, Boolean.getBoolean("preflight.desktopSmoke"));
    }

    static DesktopSmokeLiveReport start(
            Path adapterReport, AdapterMode adapterMode, boolean enabled) throws IOException {
        Path absolute = adapterReport.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException("Adapter report has no parent directory");
        return new DesktopSmokeLiveReport(
                parent.resolve("runtime-frame-report.json"),
                parent.resolve("runtime-adapter-health.json"),
                adapterMode,
                enabled);
    }

    boolean enabled() {
        return publisher != null;
    }

    String writeProblem() {
        return writeProblem;
    }

    private void publishLoop() {
        while (!closed.get()) {
            try {
                Thread.sleep(PUBLISH_INTERVAL_MILLIS);
                if (!closed.get()) publish();
            } catch (InterruptedException interrupted) {
                if (!closed.get()) Thread.currentThread().interrupt();
                return;
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable error) {
                writeProblem = error.getClass().getSimpleName() + ": " + error.getMessage();
            }
        }
    }

    private synchronized void publish() throws IOException {
        ProcessHandle process = ProcessHandle.current();
        Instant generatedAt = Instant.now();
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("pid", process.pid());
        identity.put("processStartedAt", process.info().startInstant().orElse(null));
        identity.put("generatedAt", generatedAt);

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("format", FRAME_FORMAT);
        frame.putAll(identity);
        frame.put(FrameTimeTelemetry.REPORT, FrameTimeRuntime.telemetry());
        frame.put("frameLimiterPacing", FrameLimiterPacingRuntime.telemetry());
        frame.put("campaignCallTimes", CampaignCallTimeRuntime.telemetry());
        frame.put("campaignEngineTimes", CampaignEngineTimeRuntime.telemetry());
        frame.put("campaignLocationEconomyTimes", CampaignLocationEconomyTimeRuntime.telemetry());
        frame.put("campaignMarketFleetTimes", CampaignMarketFleetTimeRuntime.telemetry());
        frame.put("campaignEntityMaintenance", CampaignEntityMaintenanceRuntime.telemetry());
        atomicWrite(frameDestination, Json.object(frame) + System.lineSeparator());

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("format", HEALTH_FORMAT);
        health.putAll(identity);
        health.put("adapterMode", adapterMode.optionValue());
        health.put("runtimeSemanticState", RuntimeSemanticState.telemetry());
        health.put("adapterTransformationCache", AdapterTransformationCache.telemetry());
        health.put("mergedReadCache", MergedReadCacheRuntime.telemetry());
        health.put("preparedTextures", TextureCompatibilityRuntime.telemetry());
        health.put("preparedAudio", PreparedAudioRuntime.report());
        health.put("memoryPressure", MacMemoryWarningRuntime.telemetry());
        health.put("combatRuntimeIntegrity", CombatRuntimeIntegrityRuntime.telemetry());
        atomicWrite(healthDestination, Json.object(health) + System.lineSeparator());
        writeProblem = null;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true) || publisher == null) return;
        publisher.interrupt();
        try {
            publisher.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try {
            publish();
        } catch (IOException error) {
            writeProblem = error.getClass().getSimpleName() + ": " + error.getMessage();
            System.err.println("[Preflight] Failed to finalize desktop smoke evidence: "
                    + error.getMessage());
        }
    }

    private static void atomicWrite(Path destination, String value) throws IOException {
        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = destination.resolveSibling(destination.getFileName()
                + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }
}
