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
                if (observed != null) {
                    try {
                        restoreStillOwnedKeys(store, observed);
                    } catch (Exception rollbackRefused) {
                        failed.addSuppressed(rollbackRefused);
                    }
                }
                throw failed;
            }

            if (!preferencePublication.publishedAsRequested()) {
                GameLaunchPreferences.PreferenceStateChangedException stale =
                        new GameLaunchPreferences.PreferenceStateChangedException(
                                "The launch settings changed while Preflight was publishing them;"
                                        + " the newer values were kept. Review the current settings and try again.");
                try {
                    restoreStillOwnedKeys(store, preferencePublication);
                } catch (Exception rollbackRefused) {
                    stale.addSuppressed(rollbackRefused);
                }
                throw stale;
            }
        }

        try {
            JvmMemorySettings.UpdateResult memoryUpdate = memoryMiB == null
                    ? null : memoryUpdater.update(installRoot, memoryMiB);
            return new Outcome(preferenceBackup, memoryUpdate);
        } catch (Exception failed) {
            if (preferencePublication != null) {
                try {
                    restoreStillOwnedKeys(store, preferencePublication);
                } catch (Exception rollbackRefused) {
                    failed.addSuppressed(rollbackRefused);
                }
            }
            throw failed;
        }
    }

    /**
     * Compensates each touched key independently. A key is eligible only if Preflight observed its
     * requested value immediately after publication and the current raw value still equals that
     * publication. Touched keys that changed externally are preserved; unrelated keys are never
     * written. The method reports that preservation after flushing any keys Preflight still owns.
     */
    private static void restoreStillOwnedKeys(
            GameLaunchPreferences.Store store,
            GameLaunchPreferences.AppliedChange change) throws Exception {
        GameLaunchPreferences.Generation current = GameLaunchPreferences.generation(store);
        boolean restored = false;
        boolean preservedNewerValue = false;
        for (String key : change.touchedKeys()) {
            boolean observedAsPreflight = Objects.equals(
                    change.observedAfter().get(key), change.expectedAfter().get(key));
            boolean stillPreflight = Objects.equals(
                    current.get(key), change.expectedAfter().get(key));
            if (!observedAsPreflight || !stillPreflight) {
                preservedNewerValue = true;
                continue;
            }
            String before = change.before().get(key);
            if (before == null) store.remove(key);
            else store.put(key, before);
            restored = true;
        }
        if (restored) store.flush();
        if (preservedNewerValue) {
            throw new GameLaunchPreferences.PreferenceStateChangedException(
                    "The launch settings changed after Preflight updated them; the newer values were kept."
                            + " Review the current settings before applying another change.");
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
