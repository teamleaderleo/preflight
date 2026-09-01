package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PathContainment;
import dev.starsector.preflight.core.PreparedTextureAccessOrderIO;
import dev.starsector.preflight.core.PreparedTexturePrefetchOrderIO;
import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Records the logical textures a real launch asks for, including Minimal cache misses. */
public final class TextureAccessLearningRuntime {
    private static final int MAX_PATHS = 100_000;
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean();
    private static State state = State.disabled();

    private TextureAccessLearningRuntime() {
    }

    static synchronized void beginSession() {
        complete();
        state = State.disabled();
    }

    static synchronized boolean configure(Path cacheDirectory, String profileFingerprint) {
        if (cacheDirectory == null || profileFingerprint == null) {
            state = State.disabled();
            return false;
        }
        try {
            Hashes.decodeSha256(profileFingerprint);
            Path cacheRoot = PathContainment.realDirectory(cacheDirectory);
            Path target = PreparedTextureAccessOrderIO.path(cacheRoot, profileFingerprint);
            Path prefetchTarget = PreparedTexturePrefetchOrderIO.path(cacheRoot, profileFingerprint);
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            LinkedHashSet<String> prefetchPaths = new LinkedHashSet<>();
            if (Files.isRegularFile(target)) {
                try {
                    paths.addAll(PreparedTextureAccessOrderIO.read(target, profileFingerprint));
                } catch (IOException | IllegalArgumentException ignored) {
                    // A damaged tuning hint must not disable fresh learning or affect launch.
                }
            }
            if (Files.isRegularFile(prefetchTarget)) {
                try {
                    prefetchPaths.addAll(PreparedTexturePrefetchOrderIO.read(
                            prefetchTarget, profileFingerprint));
                } catch (IOException | IllegalArgumentException ignored) {
                    // A damaged tuning hint must not affect launch or fresh learning.
                }
            }
            state = new State(
                    profileFingerprint, target, prefetchTarget, paths, prefetchPaths, true);
            ensureShutdownHook();
            return true;
        } catch (IOException | IllegalArgumentException error) {
            state = State.disabled();
            return false;
        }
    }

    static synchronized void observe(String logicalPath) {
        if (!state.ready || state.paths.size() >= MAX_PATHS) {
            return;
        }
        try {
            if (state.paths.add(ResourceIndex.normalizeLogicalPath(logicalPath))) {
                state.dirty = true;
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid game input remains the original loader's problem.
        }
    }

    static synchronized void observePrefetch(String logicalPath) {
        if (!state.ready || state.prefetchPaths.size() >= MAX_PATHS) {
            return;
        }
        try {
            if (state.prefetchPaths.add(ResourceIndex.normalizeLogicalPath(logicalPath))) {
                state.prefetchDirty = true;
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid game input remains the original loader's problem.
        }
    }

    static synchronized void complete() {
        if (!state.ready) {
            return;
        }
        if (state.dirty && !state.paths.isEmpty()) {
            try {
                PreparedTextureAccessOrderIO.write(
                        state.target, state.profileFingerprint, List.copyOf(state.paths));
                state.dirty = false;
            } catch (IOException | IllegalArgumentException ignored) {
                // Learning only tunes a later preparation. It never controls this launch.
            }
        }
        if (state.prefetchDirty && !state.prefetchPaths.isEmpty()) {
            try {
                PreparedTexturePrefetchOrderIO.write(
                        state.prefetchTarget,
                        state.profileFingerprint,
                        List.copyOf(state.prefetchPaths));
                state.prefetchDirty = false;
            } catch (IOException | IllegalArgumentException ignored) {
                // Learning only tunes a later launch. It never controls this launch.
            }
        }
    }

    private static void ensureShutdownHook() {
        if (!SHUTDOWN_HOOK_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(
                new Thread(TextureAccessLearningRuntime::complete, "preflight-texture-access-order"));
    }

    static synchronized List<String> snapshot() {
        return List.copyOf(state.prefetchPaths);
    }

    private static final class State {
        private final String profileFingerprint;
        private final Path target;
        private final Path prefetchTarget;
        private final LinkedHashSet<String> paths;
        private final LinkedHashSet<String> prefetchPaths;
        private final boolean ready;
        private boolean dirty;
        private boolean prefetchDirty;

        private State(
                String profileFingerprint,
                Path target,
                Path prefetchTarget,
                LinkedHashSet<String> paths,
                LinkedHashSet<String> prefetchPaths,
                boolean ready) {
            this.profileFingerprint = profileFingerprint;
            this.target = target;
            this.prefetchTarget = prefetchTarget;
            this.paths = paths;
            this.prefetchPaths = prefetchPaths;
            this.ready = ready;
        }

        private static State disabled() {
            return new State(
                    null, null, null, new LinkedHashSet<>(), new LinkedHashSet<>(), false);
        }
    }
}
