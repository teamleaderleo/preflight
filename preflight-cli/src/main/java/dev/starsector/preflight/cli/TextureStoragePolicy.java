package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.PreparedTextureIO;
import java.util.Locale;

/** User-facing space/time policy for exact prepared texture blobs. */
enum TextureStoragePolicy {
    FASTEST(PreparedTextureIO.StorageCodec.RAW, false),
    BALANCED(PreparedTextureIO.StorageCodec.LZ4, true);

    static final TextureStoragePolicy DEFAULT = BALANCED;

    private final PreparedTextureIO.StorageCodec codec;
    private final boolean rawWhenCompressionIsIneffective;

    TextureStoragePolicy(
            PreparedTextureIO.StorageCodec codec, boolean rawWhenCompressionIsIneffective) {
        this.codec = codec;
        this.rawWhenCompressionIsIneffective = rawWhenCompressionIsIneffective;
    }

    PreparedTextureIO.StorageCodec codec() {
        return codec;
    }

    boolean rawWhenCompressionIsIneffective() {
        return rawWhenCompressionIsIneffective;
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
