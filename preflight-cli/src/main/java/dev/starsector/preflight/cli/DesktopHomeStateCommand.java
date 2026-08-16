package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reads the three independent documents needed by the first desktop page in one JVM. */
final class DesktopHomeStateCommand {
    private static final String FORMAT = "starsector-preflight-desktop-home-state-v1";
    private static final int MAX_ERROR_CHARACTERS = 1_000;
    // On the reviewed 61,693-file profile, six matched eight workers' ~1.02 s wall time while
    // reducing combined user + system CPU time. Preparation and launch keep their own widths.
    private static final int HOME_SCAN_WORKERS = 6;

    private DesktopHomeStateCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        if (offset >= args.length || !"--game".equals(args[offset]) || offset + 2 != args.length) {
            throw new IllegalArgumentException(
                    "Expected desktop bridge request: desktop home-state --game <path>");
        }
        Path installRoot = InstallRoot.resolve(Path.of(args[offset + 1]));
        System.out.println(Json.object(read(PreflightHome.current(), installRoot)));
        return 0;
    }

    static Map<String, Object> read(PreflightHome home, Path installRoot) throws Exception {
        ExecutorService reads = Executors.newFixedThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "preflight-desktop-home-read");
            thread.setDaemon(true);
            return thread;
        });
        try {
            CompletableFuture<Result> cache = readAsync(reads, "cacheInspection", () ->
                    CacheCommand.inspect(
                            home,
                            CacheCommand.currentProfile(installRoot, null, HOME_SCAN_WORKERS)));
            CompletableFuture<Result> profiles = readAsync(reads, "profiles", () ->
                    ProfileCommand.describeList(home, installRoot));
            CompletableFuture<Result> launchSettings = readAsync(reads, "launchSettings", () ->
                    LaunchSettingsCommand.read(installRoot));
            CompletableFuture.allOf(cache, profiles, launchSettings).join();

            Map<String, Object> errors = new LinkedHashMap<>();
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("format", FORMAT);
            document.put("installRoot", installRoot.toAbsolutePath().normalize());
            put(document, errors, cache.join());
            put(document, errors, profiles.join());
            put(document, errors, launchSettings.join());
            document.put("errors", errors);
            return document;
        } finally {
            reads.shutdownNow();
        }
    }

    private static CompletableFuture<Result> readAsync(
            ExecutorService executor, String field, CheckedRead read) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new Result(field, read.get(), null);
            } catch (Exception failure) {
                String message = failure.getMessage();
                String normalized = message == null || message.isBlank()
                        ? failure.getClass().getSimpleName()
                        : message.replaceAll("\\s+", " ").strip();
                return new Result(
                        field,
                        null,
                        normalized.substring(0, Math.min(normalized.length(), MAX_ERROR_CHARACTERS)));
            }
        }, executor).exceptionally(failure -> {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause()
                    : failure;
            return new Result(field, null, cause.getClass().getSimpleName());
        });
    }

    private static void put(
            Map<String, Object> document, Map<String, Object> errors, Result result) {
        document.put(result.field(), result.value());
        if (result.error() != null) errors.put(result.field(), result.error());
    }

    @FunctionalInterface
    private interface CheckedRead {
        Map<String, Object> get() throws Exception;
    }

    private record Result(String field, Map<String, Object> value, String error) {
    }
}
