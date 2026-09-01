package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers the content-derived cache identities for one freshly validated resource profile.
 *
 * <p>The resource profile is rebuilt from the selected installation before this receipt is read.
 * That rebuild walks every enabled root and binds the fingerprint to enabled-mod order plus every
 * provider's path, size, and modification time. {@link CurrentTextureCache} then requires the
 * rebuilt index to equal the prepared index exactly. A matching receipt therefore avoids a second,
 * redundant pass that opens and hashes every dependency file merely to recover artifact names that
 * were already derived for this exact validated profile.</p>
 */
final class LaunchProfileSelectionReceipt {
    private static final String FORMAT = "starsector-preflight-launch-profile-selection-v1";

    private LaunchProfileSelectionReceipt() {
    }

    static Path directory(Path cache) {
        return cache.resolve("launch-profile-selections");
    }

    static Path path(Path cache, String profile) {
        requireFingerprint(profile);
        return directory(cache).resolve(profile + ".spls");
    }

    static void write(Path cache, String profile, Selection selection) throws IOException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("format", FORMAT);
        value.put("profileFingerprint", profile);
        value.put("variantJson", selection.variantJson());
        value.put("weaponJson", selection.weaponJson());
        value.put("projectileJson", selection.projectileJson());
        value.put("hullJson", selection.hullJson());
        value.put("rulesCsv", selection.rulesCsv());
        if (selection.ruleCommand() != null) {
            value.put("ruleCommand", selection.ruleCommand());
        }
        value.put("mergedRead", selection.mergedRead());
        writeAtomic(path(cache, profile).toAbsolutePath().normalize(),
                Json.object(value) + System.lineSeparator());
    }

    static Selection read(Path receipt, String profile) throws IOException {
        Map<String, Object> value;
        try {
            value = StrictJson.object(Files.readString(receipt));
        } catch (RuntimeException error) {
            throw new IOException("launch-profile selection receipt is unreadable", error);
        }
        if (!FORMAT.equals(value.get("format"))
                || !profile.equals(value.get("profileFingerprint"))) {
            throw new IOException("launch-profile selection receipt identity differs");
        }
        return new Selection(
                identity(value, "variantJson", true),
                identity(value, "weaponJson", true),
                identity(value, "projectileJson", true),
                identity(value, "hullJson", true),
                identity(value, "rulesCsv", true),
                identity(value, "ruleCommand", false),
                identity(value, "mergedRead", true));
    }

    private static String identity(Map<String, Object> value, String name, boolean required)
            throws IOException {
        Object raw = value.get(name);
        if (raw == null && !required) {
            return null;
        }
        if (!(raw instanceof String identity) || !identity.matches("[0-9a-f]{64}")) {
            throw new IOException("launch-profile selection receipt has an invalid " + name);
        }
        return identity;
    }

    private static void requireFingerprint(String profile) {
        if (profile == null || !profile.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Profile fingerprint must be 64 lowercase hexadecimal characters");
        }
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(
                target.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            Files.writeString(
                    temporary,
                    content,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    record Selection(
            String variantJson,
            String weaponJson,
            String projectileJson,
            String hullJson,
            String rulesCsv,
            String ruleCommand,
            String mergedRead) {
    }
}
