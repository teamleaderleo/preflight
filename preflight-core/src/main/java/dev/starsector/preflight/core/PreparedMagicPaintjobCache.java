package dev.starsector.preflight.core;

import java.util.Arrays;

/** Profile-bound, implementation-neutral payload for MagicLib's paintjob catalog. */
public record PreparedMagicPaintjobCache(String profileIdentitySha256, byte[] payload) {
    public static final int FORMAT_VERSION = 1;

    public PreparedMagicPaintjobCache {
        Hashes.decodeSha256(profileIdentitySha256);
        payload = Arrays.copyOf(payload, payload.length);
        if (payload.length == 0) {
            throw new IllegalArgumentException("MagicLib paintjob cache payload is empty");
        }
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
