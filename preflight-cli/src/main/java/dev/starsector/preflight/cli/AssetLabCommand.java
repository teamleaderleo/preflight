package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ImageResampler;
import dev.starsector.preflight.core.Json;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * The Asset Lab: offline, opt-in asset transformations, kept deliberately separate from the
 * speed-track {@code texture} cache commands so a fidelity or footprint overlay can never wander
 * into a speed measurement.
 *
 * <p>Its first transformation is the honest one — shrinking oversized textures. {@code scan
 * --vram-budget} says how far over a profile is and {@code scan --max-texture-size} projects what
 * capping would save; this writes the capped textures out as a drop-in override mod so the
 * projection can actually be taken. Nothing in the user's installation is read-modify-written: the
 * pack is a new directory, and un-taking the change is disabling one mod.
 */
final class AssetLabCommand {
    private static final int CHANNELS_RGB = 3;
    private static final int CHANNELS_RGBA = 4;
    /**
     * Re-encode quality for JPEG sources. High enough that the generational loss is far below the
     * halving itself, which is the whole point of the operation; the default 0.75 is not.
     */
    private static final float JPEG_QUALITY = 0.95f;

    private AssetLabCommand() {
    }

    static int execute(String[] args, int offset) throws IOException {
        if (offset >= args.length) {
            throw new IllegalArgumentException("Expected: assets shrink ...");
        }
        return switch (args[offset]) {
            case "shrink" -> shrink(ShrinkOptions.parse(args, offset + 1));
            case "compression-probe" -> compressionProbe(args, offset + 1);
            default -> throw new IllegalArgumentException("Unknown assets command: " + args[offset]);
        };
    }

    /**
     * Measures the perceptual cost of block compression on this profile's own art. Writes nothing:
     * the output is evidence about whether the runtime work is worth attempting.
     */
    private static int compressionProbe(String[] args, int offset) throws IOException {
        Path game = null;
        Path launcher = null;
        int samples = 400;
        boolean opaqueAsBc1 = true;
        for (int i = offset; i < args.length; i++) {
            switch (args[i]) {
                case "--game" -> game = Path.of(requireArg(args, ++i, "--game"));
                case "--launcher" -> launcher = Path.of(requireArg(args, ++i, "--launcher"));
                case "--samples" -> samples = Integer.parseInt(requireArg(args, ++i, "--samples"));
                case "--all-bc3" -> opaqueAsBc1 = false;
                default -> throw new IllegalArgumentException("Unknown assets compression-probe option: " + args[i]);
            }
        }
        DiscoveryResult discovery = StarsectorDiscovery.discover(
                Platform.current(),
                Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("user.dir")),
                System.getenv(),
                game,
                launcher);
        LaunchTarget target = discovery.selected();
        if (target == null) {
            System.err.println("Preflight could not locate Starsector. Run `doctor` or provide --game.");
            return 3;
        }
        ProfileCensus.Result census = ProfileCensus.scan(target.installRoot());
        Map<String, Object> report = new LinkedHashMap<>(
                CompressionProbe.run(census.measuredWinners(), new CompressionProbe.Options(samples, opaqueAsBc1)));
        report.put("installRoot", target.installRoot());
        report.put("profileTextures", (long) census.measuredWinners().size());
        System.out.println(Json.object(report));
        return 0;
    }

    private static String requireArg(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static int shrink(ShrinkOptions options) throws IOException {
        DiscoveryResult discovery = StarsectorDiscovery.discover(
                Platform.current(),
                Path.of(System.getProperty("user.home")),
                Path.of(System.getProperty("user.dir")),
                System.getenv(),
                options.game,
                options.launcher);
        LaunchTarget target = discovery.selected();
        if (target == null) {
            System.err.println("Preflight could not locate Starsector. Run `doctor` or provide --game.");
            return 3;
        }

        Path outDir = options.outDir.toAbsolutePath().normalize();
        if (!options.dryRun) {
            requireWritablePackDirectory(outDir, options.force);
        }

        ProfileCensus.Result census = ProfileCensus.scan(
                target.installRoot(),
                new ProfileCensus.Options(OptionalLong.empty(), OptionalInt.of(options.maxTextureSize)));

        List<Candidate> candidates = new ArrayList<>();
        for (ProfileCensus.TextureWinner winner : census.measuredWinners()) {
            int halvings = ProfileCensus.halvingsToFit(
                    winner.dimensions().width(), winner.dimensions().height(), options.maxTextureSize);
            if (halvings > 0) {
                candidates.add(new Candidate(winner, halvings));
            }
        }
        // Biggest saving first, so --limit takes the textures that matter most.
        candidates.sort(Comparator.comparingLong((Candidate candidate) -> candidate.savedBytes()).reversed()
                .thenComparing(candidate -> candidate.winner().logicalPath()));

        List<Map<String, Object>> written = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        long writtenSavedBytes = 0;
        long skippedSavedBytes = 0;
        int budget = options.limit.orElse(Integer.MAX_VALUE);
        for (Candidate candidate : candidates) {
            String reason = unsupportedReason(candidate);
            if (reason == null && written.size() >= budget) {
                reason = "beyond --limit";
            }
            if (reason != null) {
                skipped.add(candidate.toMap(reason));
                skippedSavedBytes += candidate.savedBytes();
                continue;
            }
            try {
                if (!options.dryRun) {
                    writeShrunk(candidate, outDir);
                }
                written.add(candidate.toMap(null));
                writtenSavedBytes += candidate.savedBytes();
            } catch (IOException | RuntimeException error) {
                skipped.add(candidate.toMap("failed: " + message(error)));
                skippedSavedBytes += candidate.savedBytes();
            }
        }

        if (!options.dryRun && !written.isEmpty()) {
            writeModInfo(options, outDir, written.size());
        }

        long currentFloor = 0;
        for (ProfileCensus.TextureWinner winner : census.measuredWinners()) {
            currentFloor += winner.dimensions().decodedBytes();
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("installRoot", target.installRoot());
        report.put("modDirectory", outDir);
        report.put("dryRun", options.dryRun);
        report.put("maxTextureSizePixels", options.maxTextureSize);
        report.put("method", "iterated halving to max(1, size >> 1), alpha-premultiplied area average "
                + "(identical to a 2x2 box on even dimensions, and unlike one it reads the whole "
                + "source on odd ones); written back in the source container (PNG, or JPEG re-encoded "
                + "at quality " + JPEG_QUALITY + ") so the logical path and decoded channel count are "
                + "preserved");
        report.put("oversizedTextures", (long) candidates.size());
        report.put("texturesWritten", (long) written.size());
        report.put("texturesSkipped", (long) skipped.size());
        report.put("currentFloorBytes", currentFloor);
        report.put("writtenSavedBytes", writtenSavedBytes);
        report.put("unrealizedSavedBytes", skippedSavedBytes);
        report.put("projectedFloorBytes", currentFloor - writtenSavedBytes);
        report.put("overrideRequirement", "the pack only takes effect where it wins the override, so "
                + "enable it after every mod it replaces a texture for; disabling it restores the originals");
        report.put("written", written);
        report.put("skipped", skipped);
        System.out.println(Json.object(report));
        return 0;
    }

    /**
     * Why this texture cannot be shrunk faithfully, or {@code null} if it can. The bar is that the
     * rewritten file must decode to the same channel count as the original, otherwise the delivered
     * saving would not match the projection — a 3-channel source re-encoded with an alpha channel
     * would grow per pixel even as it shrank in pixels. It is also written back in the source's own
     * container, since the game resolves a texture by its exact logical path including extension.
     */
    private static String unsupportedReason(Candidate candidate) {
        int channels = candidate.winner().dimensions().channels();
        return switch (container(candidate.winner().logicalPath())) {
            case PNG -> channels == CHANNELS_RGB || channels == CHANNELS_RGBA
                    ? null
                    : "unsupported PNG channel count: " + channels;
            // A JPEG has no alpha, and a 1-component (greyscale) or 4-component (CMYK) one is both
            // rare in game art and not round-trippable through the default writer.
            case JPEG -> channels == CHANNELS_RGB ? null : "unsupported JPEG component count: " + channels;
            case OTHER -> "unsupported container: " + candidate.winner().logicalPath();
        };
    }

    private static Container container(String logicalPath) {
        String path = logicalPath.toLowerCase(Locale.ROOT);
        if (path.endsWith(".png")) {
            return Container.PNG;
        }
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            return Container.JPEG;
        }
        return Container.OTHER;
    }

    private enum Container {
        PNG,
        JPEG,
        OTHER
    }

    private static void writeShrunk(Candidate candidate, Path outDir) throws IOException {
        Path destination = outDir.resolve(candidate.winner().logicalPath()).normalize();
        if (!destination.startsWith(outDir)) {
            throw new IOException("Refusing to write outside the pack: " + candidate.winner().logicalPath());
        }
        BufferedImage image = ImageIO.read(candidate.winner().file().toFile());
        if (image == null) {
            throw new IOException("No image reader accepted the source");
        }
        boolean alpha = candidate.winner().dimensions().channels() == CHANNELS_RGBA;
        for (int step = 0; step < candidate.halvings(); step++) {
            image = halve(image, alpha);
        }
        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }
        switch (container(candidate.winner().logicalPath())) {
            case PNG -> {
                if (!ImageIO.write(image, "png", destination.toFile())) {
                    throw new IOException("No PNG writer was available");
                }
            }
            case JPEG -> writeJpeg(image, destination);
            case OTHER -> throw new IOException("Unsupported container: " + candidate.winner().logicalPath());
        }
    }

    /** Writes a JPEG at {@link #JPEG_QUALITY}; {@code ImageIO.write} would silently use 0.75. */
    private static void writeJpeg(BufferedImage image, Path destination) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer was available");
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(destination.toFile())) {
            if (output == null) {
                throw new IOException("Could not open the destination for writing: " + destination);
            }
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
    }

    /**
     * Halves an image using {@link ImageResampler}'s area average: each output pixel averages exactly
     * the source interval it covers, premultiplied by alpha.
     *
     * <p>The filter definition lives in core so this and {@code BlockTextureBaker}'s mip chain cannot
     * drift apart — a projection made by one and delivered by the other has to agree. See
     * {@link ImageResampler} for why the obvious 2x2 box is wrong on odd dimensions, which is what
     * this method used to do.
     *
     * <p>Rows are read two or three at a time rather than converting the whole image, because
     * {@code assets shrink} runs over hundreds of textures and the largest are already tens of
     * megabytes as a {@code BufferedImage}. The weights come from core; only the traversal is local.
     */
    static BufferedImage halve(BufferedImage source, boolean alpha) {
        int outWidth = ImageResampler.halved(source.getWidth());
        int outHeight = ImageResampler.halved(source.getHeight());
        ImageResampler.Taps horizontal = ImageResampler.taps(source.getWidth(), outWidth);
        ImageResampler.Taps vertical = ImageResampler.taps(source.getHeight(), outHeight);
        BufferedImage target = new BufferedImage(
                outWidth, outHeight, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);

        int sourceWidth = source.getWidth();
        int[] rows = new int[sourceWidth * vertical.maxWeightCount()];
        int[] output = new int[outWidth];
        for (int y = 0; y < outHeight; y++) {
            int rowCount = vertical.weightCount(y);
            source.getRGB(0, vertical.first(y), sourceWidth, rowCount, rows, 0, sourceWidth);
            for (int x = 0; x < outWidth; x++) {
                double alphaSum = 0;
                double redSum = 0;
                double greenSum = 0;
                double blueSum = 0;
                for (int row = 0; row < rowCount; row++) {
                    int rowOffset = row * sourceWidth + horizontal.first(x);
                    for (int column = 0; column < horizontal.weightCount(x); column++) {
                        int pixel = rows[rowOffset + column];
                        double weight = vertical.weight(y, row) * horizontal.weight(x, column);
                        // An RGB source has no alpha plane to read; every pixel is fully covered.
                        double pixelAlpha = weight * (alpha ? (pixel >>> 24) : 255);
                        alphaSum += pixelAlpha;
                        redSum += pixelAlpha * ((pixel >> 16) & 0xFF);
                        greenSum += pixelAlpha * ((pixel >> 8) & 0xFF);
                        blueSum += pixelAlpha * (pixel & 0xFF);
                    }
                }
                output[x] = ImageResampler.pack(alphaSum, redSum, greenSum, blueSum);
            }
            target.setRGB(0, y, outWidth, 1, output, 0, outWidth);
        }
        return target;
    }

    private static void requireWritablePackDirectory(Path outDir, boolean force) throws IOException {
        if (Files.exists(outDir) && !force) {
            boolean empty;
            try (var entries = Files.list(outDir)) {
                empty = entries.findAny().isEmpty();
            }
            if (!empty) {
                throw new IOException(
                        "Refusing to write into a non-empty directory (pass --force to overwrite): " + outDir);
            }
        }
        Files.createDirectories(outDir);
    }

    private static void writeModInfo(ShrinkOptions options, Path outDir, int textureCount) throws IOException {
        Map<String, Object> modInfo = new LinkedHashMap<>();
        modInfo.put("id", options.modId);
        modInfo.put("name", options.modName);
        modInfo.put("author", "preflight");
        modInfo.put("version", "0.1");
        modInfo.put("gameVersion", options.gameVersion);
        modInfo.put("description", "Overrides " + textureCount + " oversized textures with copies capped at "
                + options.maxTextureSize + " pixels on the long edge, to cut decoded VRAM. Enable after the mods "
                + "it overrides. Disable to restore the originals.");
        modInfo.put("utility", true);
        Files.writeString(outDir.resolve("mod_info.json"), Json.object(modInfo) + "\n", StandardCharsets.UTF_8);
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record Candidate(ProfileCensus.TextureWinner winner, int halvings) {
        int projectedWidth() {
            return Math.max(1, winner.dimensions().width() >> halvings);
        }

        int projectedHeight() {
            return Math.max(1, winner.dimensions().height() >> halvings);
        }

        long projectedBytes() {
            return (long) projectedWidth() * projectedHeight() * winner.dimensions().channels();
        }

        long savedBytes() {
            return winner.dimensions().decodedBytes() - projectedBytes();
        }

        Map<String, Object> toMap(String reason) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("modId", winner.modId());
            values.put("path", winner.logicalPath());
            values.put("width", winner.dimensions().width());
            values.put("height", winner.dimensions().height());
            values.put("channels", winner.dimensions().channels());
            values.put("decodedBytes", winner.dimensions().decodedBytes());
            values.put("projectedWidth", projectedWidth());
            values.put("projectedHeight", projectedHeight());
            values.put("projectedDecodedBytes", projectedBytes());
            values.put("savedBytes", savedBytes());
            if (reason != null) {
                values.put("reason", reason);
            }
            return values;
        }
    }

    private static final class ShrinkOptions {
        private Path game;
        private Path launcher;
        private Path outDir;
        private int maxTextureSize = -1;
        private OptionalInt limit = OptionalInt.empty();
        private boolean dryRun;
        private boolean force;
        private String modId = "preflight_shrink_pack";
        private String modName = "Preflight Shrink Pack";
        private String gameVersion = "0.98a-RC8";

        static ShrinkOptions parse(String[] args, int offset) {
            ShrinkOptions options = new ShrinkOptions();
            for (int i = offset; i < args.length; i++) {
                switch (args[i]) {
                    case "--game" -> options.game = Path.of(requireValue(args, ++i, "--game"));
                    case "--launcher" -> options.launcher = Path.of(requireValue(args, ++i, "--launcher"));
                    case "--out-dir" -> options.outDir = Path.of(requireValue(args, ++i, "--out-dir"));
                    case "--max-texture-size" -> options.maxTextureSize =
                            ScanOptions.parseTextureSize(requireValue(args, ++i, "--max-texture-size"));
                    case "--limit" -> options.limit =
                            OptionalInt.of(parseLimit(requireValue(args, ++i, "--limit")));
                    case "--dry-run" -> options.dryRun = true;
                    case "--force" -> options.force = true;
                    case "--mod-id" -> options.modId = requireValue(args, ++i, "--mod-id");
                    case "--mod-name" -> options.modName = requireValue(args, ++i, "--mod-name");
                    case "--game-version" -> options.gameVersion = requireValue(args, ++i, "--game-version");
                    default -> throw new IllegalArgumentException("Unknown assets shrink option: " + args[i]);
                }
            }
            if (options.maxTextureSize < 1) {
                throw new IllegalArgumentException("assets shrink requires --max-texture-size <pixels>");
            }
            if (options.outDir == null) {
                throw new IllegalArgumentException("assets shrink requires --out-dir <mod directory>");
            }
            return options;
        }

        private static int parseLimit(String raw) {
            int limit;
            try {
                limit = Integer.parseInt(raw.trim());
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Not a valid --limit: " + raw);
            }
            if (limit < 1) {
                throw new IllegalArgumentException("--limit must be at least 1: " + raw);
            }
            return limit;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }
}
