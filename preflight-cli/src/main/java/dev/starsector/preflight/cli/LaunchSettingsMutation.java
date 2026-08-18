package dev.starsector.preflight.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Coordinates the two game-owned launch-setting surfaces without treating Preflight's process
 * lease as ownership over Starsector's launcher preferences or JVM parameter files.
 */
final class LaunchSettingsMutation {
    private LaunchSettingsMutation() {
    }

    static Outcome apply(
            GameLaunchPreferences.Store store,
            GameLaunchPreferences.Update update,
            boolean preferenceChange,
            LaunchSettingsCommand.Limits limits,
            Path installRoot,
            Integer memoryMiB,
            PreferenceBackupWriter backupWriter) throws Exception {
        return apply(
                store,
                update,
                preferenceChange,
                limits,
                installRoot,
                memoryMiB,
                backupWriter,
                ignored -> {},
                JvmMemorySettings::update);
    }

    /** Test seam for an external preference writer between the first in-lease read and publication. */
    static Outcome apply(
            GameLaunchPreferences.Store store,
            GameLaunchPreferences.Update update,
            boolean preferenceChange,
            LaunchSettingsCommand.Limits limits,
            Path installRoot,
            Integer memoryMiB,
            PreferenceBackupWriter backupWriter,
            PreferenceHook beforePreferenceCommit,
            MemoryUpdater memoryUpdater) throws Exception {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(backupWriter, "backupWriter");
        Objects.requireNonNull(beforePreferenceCommit, "beforePreferenceCommit");
        Objects.requireNonNull(memoryUpdater, "memoryUpdater");

        Path preferenceBackup = null;
        GameLaunchPreferences.AppliedChange preferencePublication = null;
        if (preferenceChange) {
            GameLaunchPreferences.validate(update);

            // The CLI may have displayed an earlier snapshot, but this is the first value allowed
            // to authorize a mutation. Revalidate partial/coupled values against the current raw
            // generation while the Preflight operation lease is already held.
            GameLaunchPreferences.Generation current = GameLaunchPreferences.generation(store);
            limits.validate(update, GameLaunchPreferences.read(current));

            beforePreferenceCommit.run(store);
            GameLaunchPreferences.Generation commitBase = GameLaunchPreferences.generation(store);
            if (!commitBase.equals(current)) {
                // An external/manual writer is authoritative. Adopt its untouched values for this
                // partial update only after the effective combination validates again.
                current = commitBase;
                limits.validate(update, GameLaunchPreferences.read(current));
            }

            GameLaunchPreferences.Backup before = current.backup();
            preferenceBackup = backupWriter.write(before);
            try {
                preferencePublication = GameLaunchPreferences.applyIfUnchanged(store, current, update);
            } catch (GameLaunchPreferences.PreferenceStateChangedException stale) {
                deleteUnusedBackup(preferenceBackup, stale);
                throw stale;
            } catch (GameLaunchPreferences.PreferencePublicationException failed) {
                GameLaunchPreferences.AppliedChange observed = failed.observedChange();
                if (observed != null && observed.publishedAsRequested()) {
                    try {
                        GameLaunchPreferences.restoreIfStillPublished(store, observed);
                    } catch (Exception rollbackRefused) {
                        failed.addSuppressed(rollbackRefused);
                    }
                }
                throw failed;
            }

            if (!preferencePublication.publishedAsRequested()) {
                throw new GameLaunchPreferences.PreferenceStateChangedException(
                        "The launch settings changed while Preflight was publishing them; the current values were kept."
                                + " Review the current settings and try again.");
            }
        }

        try {
            JvmMemorySettings.UpdateResult memoryUpdate = memoryMiB == null
                    ? null : memoryUpdater.update(installRoot, memoryMiB);
            return new Outcome(preferenceBackup, memoryUpdate);
        } catch (Exception failed) {
            if (preferencePublication != null) {
                try {
                    GameLaunchPreferences.restoreIfStillPublished(store, preferencePublication);
                } catch (Exception rollbackRefused) {
                    failed.addSuppressed(rollbackRefused);
                }
            }
            throw failed;
        }
    }

    private static void deleteUnusedBackup(Path backup, Exception primary) {
        if (backup == null) return;
        try {
            Files.deleteIfExists(backup);
        } catch (Exception cleanupFailed) {
            primary.addSuppressed(cleanupFailed);
        }
    }

    record Outcome(
            Path preferenceBackup,
            JvmMemorySettings.UpdateResult memoryUpdate) {
    }

    @FunctionalInterface
    interface PreferenceBackupWriter {
        Path write(GameLaunchPreferences.Backup backup) throws Exception;
    }

    @FunctionalInterface
    interface PreferenceHook {
        void run(GameLaunchPreferences.Store store) throws Exception;
    }

    @FunctionalInterface
    interface MemoryUpdater {
        JvmMemorySettings.UpdateResult update(Path installRoot, int memoryMiB) throws Exception;
    }
}
