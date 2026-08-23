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

/** Records how an exact profile's currently published texture pack was prepared. */
final class TexturePreparationReceipt {
    private static final String FORMAT = "starsector-preflight-texture-preparation-v1";

    private TexturePreparationReceipt() {
    }

    static Path directory(Path cache) {
        return cache.resolve("texture-preparations");
    }

    static Path path(Path cache, String profile) {
        requireFingerprint(profile);
        return directory(cache).resolve(profile + ".sptp");
    }

    static void write(
            Path cache,
            String profile,
            TextureStoragePolicy storage,
            TexturePreparationScope scope) throws IOException {
        Path target = path(cache, profile).toAbsolutePath().normalize();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("format", FORMAT);
        value.put("profileFingerprint", profile);
        value.put("textureStorage", storage.optionValue());
        value.put("textureScope", scope.optionValue());
        writeAtomic(target, Json.object(value) + System.lineSeparator());
    }

    static void remove(Path cache, String profile) throws IOException {
        Files.deleteIfExists(path(cache, profile));
    }

    static Receipt read(Path receipt, String profile) throws IOException {
        Map<String, Object> value;
        try {
            value = StrictJson.object(Files.readString(receipt));
        } catch (RuntimeException error) {
            throw new IOException("texture-preparation receipt is unreadable", error);
        }
        if (!FORMAT.equals(value.get("format"))
                || !profile.equals(value.get("profileFingerprint"))) {
            throw new IOException("texture-preparation receipt identity differs");
        }
        Object storage = value.get("textureStorage");
        Object scope = value.get("textureScope");
        if (!(storage instanceof String storageValue)
                || !(scope instanceof String scopeValue)) {
            throw new IOException("texture-preparation receipt is incomplete");
        }
        try {
            return new Receipt(
                    TextureStoragePolicy.parse(storageValue),
                    TexturePreparationScope.parse(scopeValue));
        } catch (IllegalArgumentException error) {
            throw new IOException("texture-preparation receipt has an unknown mode", error);
        }
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

    record Receipt(TextureStoragePolicy storage, TexturePreparationScope scope) {
    }
}
