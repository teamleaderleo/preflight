package dev.starsector.preflight.cli;

import java.nio.file.Path;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.OptionalLong;

record ScanOptions(
        Path game,
        Path launcher,
        Path output,
        OptionalLong vramBudgetBytes,
        OptionalInt maxTextureSizePixels) {

    static ScanOptions parse(String[] args, int offset) {
        Path game = null;
        Path launcher = null;
        Path output = null;
        OptionalLong vramBudgetBytes = OptionalLong.empty();
        OptionalInt maxTextureSizePixels = OptionalInt.empty();
        for (int i = offset; i < args.length; i++) {
            switch (args[i]) {
                case "--game" -> game = Path.of(requireValue(args, ++i, "--game"));
                case "--launcher" -> launcher = Path.of(requireValue(args, ++i, "--launcher"));
                case "--json" -> output = Path.of(requireValue(args, ++i, "--json"));
                case "--vram-budget" -> vramBudgetBytes =
                        OptionalLong.of(parseByteSize(requireValue(args, ++i, "--vram-budget")));
                case "--max-texture-size" -> maxTextureSizePixels =
                        OptionalInt.of(parseTextureSize(requireValue(args, ++i, "--max-texture-size")));
                default -> throw new IllegalArgumentException("Unknown scan option: " + args[i]);
            }
        }
        return new ScanOptions(game, launcher, output, vramBudgetBytes, maxTextureSizePixels);
    }

    ProfileCensus.Options censusOptions() {
        return new ProfileCensus.Options(vramBudgetBytes, maxTextureSizePixels);
    }

    /** Parses a texture edge cap in pixels. Must be a positive whole number of pixels. */
    static int parseTextureSize(String raw) {
        int pixels;
        try {
            pixels = Integer.parseInt(raw.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Not a valid texture size in pixels: " + raw);
        }
        if (pixels < 1) {
            throw new IllegalArgumentException("Texture size must be at least 1 pixel: " + raw);
        }
        return pixels;
    }

    /**
     * Parses a byte size that optionally ends in a binary-unit suffix: {@code K}, {@code M}, or
     * {@code G} (case-insensitive, powers of 1024). A bare number is bytes. Examples: {@code 4G},
     * {@code 512M}, {@code 2048}.
     */
    static long parseByteSize(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty byte size");
        }
        long multiplier = 1L;
        char last = value.charAt(value.length() - 1);
        switch (last) {
            case 'K' -> multiplier = 1024L;
            case 'M' -> multiplier = 1024L * 1024L;
            case 'G' -> multiplier = 1024L * 1024L * 1024L;
            default -> {
                if (!Character.isDigit(last)) {
                    throw new IllegalArgumentException("Unknown byte-size suffix in: " + raw
                            + ". Use a number, optionally ending in K, M, or G -- for example 4G.");
                }
            }
        }
        String digits = multiplier == 1L ? value : value.substring(0, value.length() - 1);
        long number;
        try {
            number = Long.parseLong(digits.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Not a valid byte size: " + raw);
        }
        if (number < 0) {
            throw new IllegalArgumentException("Byte size must not be negative: " + raw);
        }
        try {
            return Math.multiplyExact(number, multiplier);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Byte size too large: " + raw);
        }
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }
}
