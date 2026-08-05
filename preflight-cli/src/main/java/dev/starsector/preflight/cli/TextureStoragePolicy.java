package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.PreparedTextureIO;
import java.util.Locale;

/** User-facing space/time policy for exact prepared texture blobs. */
enum TextureStoragePolicy {
    FASTEST(PreparedTextureIO.StorageCodec.RAW),
    BALANCED(PreparedTextureIO.StorageCodec.LZ4);

    private final PreparedTextureIO.StorageCodec codec;

    TextureStoragePolicy(PreparedTextureIO.StorageCodec codec) {
        this.codec = codec;
    }

    PreparedTextureIO.StorageCodec codec() {
        return codec;
    }

    String optionValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    static TextureStoragePolicy parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "fastest" -> FASTEST;
            case "balanced" -> BALANCED;
            default -> throw new IllegalArgumentException(
                    "Unknown texture storage policy: " + value + " (expected fastest or balanced)");
        };
    }
}
