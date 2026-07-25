package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.BlockCompressor;
import dev.starsector.preflight.core.BlockTexture;
import dev.starsector.preflight.core.BlockTextureBaker;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ImageResampler;
import dev.starsector.preflight.core.TextureFidelity;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Renders a profile's art next to what block compression does to it, and next to the decision the baker
 * made about it, as one image.
 *
 * <h2>Why an image, when every texture already has a number</h2>
 *
 * <p>Because the numbers cannot see the failure that matters most. {@link TextureKind} decides which
 * textures are too precision-sensitive to compress by looking at filenames, across roughly seventy mods
 * that agree on no convention. Both of its misfires are invisible to Delta-E: a normal map that slips
 * past the filter scores <em>well</em>, precisely because normal maps are smooth and the metric is not
 * measuring the property that matters, and art that is wrongly skipped produces no number at all because
 * skipped textures are never measured.
 *
 * <p>"Is this a normal map or is it art?" has no predicate form. It is a question about what the image
 * depicts, so the only way to check the classifier is to look at the art beside its label — either with
 * human eyes or with a model that has them. This sheet is that artifact.
 *
 * <p>It also collapses the one remaining manual step in the texture program. Confirming "no visible
 * corruption" currently means launching the game on the reviewed installation; a wrong row order or a
 * swapped channel is obvious in the reconstruction column here, and this needs no GPU, no display and no
 * Starsector, so it runs anywhere the test suite does.
 *
 * <h2>What each column means</h2>
 *
 * <ul>
 *   <li><b>source</b> — the art as shipped, over a checkerboard so transparency is visible rather than
 *       guessed at.
 *   <li><b>reconstruction</b> — that art encoded and decoded again. For a shader map this is hatched
 *       rather than filled, because the policy is not to encode one, and rendering what it would have
 *       looked like would imply the number underneath it meant something.
 *   <li><b>difference</b> — per-pixel Delta-E from {@link TextureFidelity#deltaEMap}, the same
 *       measurement the gate is stated in. Black is no error, red is
 *       {@link TextureFidelity#JUST_NOTICEABLE}, yellow is {@link TextureFidelity#OBVIOUS} or worse.
 * </ul>
 *
 * <p>The difference column reduces by <em>maximum</em> rather than by average. Every other reduction in
 * this project averages, because averaging is what preserves an image; this one is not preserving an
 * image, it is looking for a defect, and a defect small enough to be averaged away is exactly the one
 * worth seeing.
 */
final class ContactSheet {
    /** Panel edge in pixels. Three panels and a caption make one cell. */
    private static final int DEFAULT_PANEL = 128;
    private static final int DEFAULT_COLUMNS = 4;
    private static final int DEFAULT_SAMPLES = 24;
    private static final int PANELS = 3;
    private static final int GAP = 8;
    private static final int CAPTION_HEIGHT = 30;
    private static final int HEADER_HEIGHT = 58;
    private static final int CHECKER = 8;

    private static final Color BACKGROUND = new Color(0x14, 0x16, 0x1a);
    private static final Color CHECKER_LIGHT = new Color(0x3a, 0x3d, 0x44);
    private static final Color CHECKER_DARK = new Color(0x2b, 0x2e, 0x34);
    private static final Color CAPTION = new Color(0xd0, 0xd4, 0xda);
    private static final Color CAPTION_DIM = new Color(0x8a, 0x90, 0x99);

    private ContactSheet() {
    }

    /**
     * @param samples how many textures to draw, spread evenly across the path-sorted profile
     * @param maxP99DeltaE the gate to report against; textures over it are drawn and labelled as such,
     *     because a sheet that showed only what passed could not explain what was refused
     * @param panel panel edge in pixels
     * @param columns cells per row
     */
    record Options(int samples, double maxP99DeltaE, int panel, int columns) {
        static Options defaults() {
            return new Options(DEFAULT_SAMPLES, TextureFidelity.JUST_NOTICEABLE, DEFAULT_PANEL, DEFAULT_COLUMNS);
        }

        Options {
            if (samples <= 0) {
                throw new IllegalArgumentException("--samples must be positive");
            }
            if (maxP99DeltaE <= 0 || Double.isNaN(maxP99DeltaE)) {
                throw new IllegalArgumentException("--max-delta-e must be a positive number");
            }
            if (panel < 32 || panel > 1024) {
                throw new IllegalArgumentException("--panel must be between 32 and 1024");
            }
            if (columns <= 0 || columns > 16) {
                throw new IllegalArgumentException("--columns must be between 1 and 16");
            }
        }
    }

    /** What the baker did, or would do, with this texture. */
    enum Disposition {
        CACHED("cached"),
        OVER_GATE("over gate"),
        SHADER_MAP("shader map"),
        UNREADABLE("unreadable");

        private final String label;

        Disposition(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    static Map<String, Object> write(
            List<ProfileCensus.TextureWinner> winners,
            Path target,
            String profileFingerprint,
            Options options) throws IOException {
        List<ProfileCensus.TextureWinner> sorted = new ArrayList<>(winners);
        // Sorted then evenly strided, so the sheet is a sample of the profile rather than of whichever
        // mod the census happened to walk first, and so the same profile always yields the same sheet.
        sorted.sort(Comparator.comparing(ProfileCensus.TextureWinner::logicalPath));
        List<Cell> cells = new ArrayList<>();
        int stride = sorted.size() <= options.samples() ? 1 : sorted.size() / options.samples();
        for (int i = 0; i < sorted.size() && cells.size() < options.samples(); i += stride) {
            cells.add(examine(sorted.get(i), options));
        }
        if (cells.isEmpty()) {
            throw new IOException("This profile has no measurable textures, so there is nothing to draw");
        }

        BufferedImage sheet = draw(cells, profileFingerprint, options);
        Path absolute = target.toAbsolutePath().normalize();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        if (!ImageIO.write(sheet, "png", absolute.toFile())) {
            throw new IOException("No PNG writer was available for " + absolute);
        }
        return report(cells, absolute, sheet, profileFingerprint, options);
    }

    /** Bakes one texture and records what the baker would decide about it, without writing a cache. */
    private static Cell examine(ProfileCensus.TextureWinner winner, Options options) {
        String path = winner.logicalPath();
        try {
            BufferedImage image = ImageIO.read(winner.file().toFile());
            if (image == null) {
                return Cell.unreadable(path);
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] source = image.getRGB(0, 0, width, height, null, 0, width);
            if (TextureKind.of(path) == TextureKind.SHADER_MAP) {
                // Deliberately not encoded, so there is nothing to reconstruct or measure. The source
                // panel is the whole evidence here: the question this cell answers is whether the art
                // is what its name claims.
                return new Cell(path, Disposition.SHADER_MAP, width, height, "-", 0, 0, source, null, null);
            }
            BlockTexture texture = BlockTextureBaker.bake(
                    Hashes.sha256(winner.file()), source, width, height, BlockTextureBaker.Mips.NONE);
            int[] decoded = BlockCompressor.decode(
                    texture.level(0), width, height, texture.format().withAlpha());
            TextureFidelity.Report fidelity = texture.fidelity();
            Disposition disposition = fidelity.p99DeltaE() > options.maxP99DeltaE()
                    ? Disposition.OVER_GATE
                    : Disposition.CACHED;
            return new Cell(path, disposition, width, height, texture.format().name(),
                    fidelity.meanDeltaE(), fidelity.p99DeltaE(),
                    source, decoded, TextureFidelity.deltaEMap(source, decoded));
        } catch (IOException | RuntimeException error) {
            return Cell.unreadable(path);
        }
    }

    private static BufferedImage draw(List<Cell> cells, String profileFingerprint, Options options) {
        int panel = options.panel();
        int cellWidth = PANELS * panel + 2 * GAP;
        int cellHeight = panel + CAPTION_HEIGHT;
        int columns = Math.min(options.columns(), cells.size());
        int rows = (cells.size() + columns - 1) / columns;
        int width = GAP + columns * (cellWidth + GAP);
        int height = HEADER_HEIGHT + GAP + rows * (cellHeight + GAP);

        BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = sheet.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setColor(BACKGROUND);
        graphics.fillRect(0, 0, width, height);
        drawHeader(graphics, width, profileFingerprint, options, cells);

        for (int i = 0; i < cells.size(); i++) {
            int column = i % columns;
            int row = i / columns;
            int x = GAP + column * (cellWidth + GAP);
            int y = HEADER_HEIGHT + GAP + row * (cellHeight + GAP);
            drawCell(graphics, sheet, cells.get(i), x, y, panel);
        }
        graphics.dispose();
        return sheet;
    }

    private static void drawHeader(
            Graphics2D graphics, int width, String profileFingerprint, Options options, List<Cell> cells) {
        graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        graphics.setColor(CAPTION);
        graphics.drawString("preflight block cache contact sheet"
                + "    source | reconstruction | difference", GAP, 20);
        graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        graphics.setColor(CAPTION_DIM);
        graphics.drawString("profile " + profileFingerprint
                + "    gate p99 dE <= " + options.maxP99DeltaE()
                + "    codec v" + BlockCompressor.CODEC_VERSION
                + "    " + cells.size() + " of the profile, evenly sampled", GAP, 36);
        graphics.drawString("difference: black none, red dE " + TextureFidelity.JUST_NOTICEABLE
                + " (just noticeable), yellow dE " + TextureFidelity.OBVIOUS
                + "+ (obvious); reduced by maximum, so small artifacts survive", GAP, 50);
        graphics.setColor(new Color(0x2b, 0x2e, 0x34));
        graphics.drawLine(0, HEADER_HEIGHT - 1, width, HEADER_HEIGHT - 1);
    }

    private static void drawCell(
            Graphics2D graphics, BufferedImage sheet, Cell cell, int x, int y, int panel) {
        checkerboard(graphics, x, y, panel);
        blit(sheet, fit(cell.original(), cell.width(), cell.height(), panel), x, y, panel);

        int reconstructionX = x + panel + GAP;
        if (cell.reconstruction() == null) {
            hatch(graphics, reconstructionX, y, panel, "not encoded");
            hatch(graphics, reconstructionX + panel + GAP, y, panel, null);
        } else {
            checkerboard(graphics, reconstructionX, y, panel);
            blit(sheet, fit(cell.reconstruction(), cell.width(), cell.height(), panel), reconstructionX, y, panel);
            blit(sheet, heat(cell.difference(), cell.width(), cell.height(), panel),
                    reconstructionX + panel + GAP, y, panel);
        }
        caption(graphics, cell, x, y + panel);
    }

    private static void caption(Graphics2D graphics, Cell cell, int x, int y) {
        graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        graphics.setColor(CAPTION);
        graphics.drawString(cell.shortPath(), x, y + 13);
        graphics.setColor(dispositionColour(cell.disposition()));
        graphics.drawString(cell.detail(), x, y + 26);
    }

    private static Color dispositionColour(Disposition disposition) {
        return switch (disposition) {
            case CACHED -> new Color(0x7d, 0xc4, 0x8f);
            case OVER_GATE -> new Color(0xd8, 0xa6, 0x57);
            case SHADER_MAP -> new Color(0x77, 0xa8, 0xd8);
            case UNREADABLE -> new Color(0xc4, 0x7d, 0x7d);
        };
    }

    private static void checkerboard(Graphics2D graphics, int x, int y, int panel) {
        for (int row = 0; row < panel; row += CHECKER) {
            for (int column = 0; column < panel; column += CHECKER) {
                graphics.setColor(((row / CHECKER) + (column / CHECKER)) % 2 == 0 ? CHECKER_DARK : CHECKER_LIGHT);
                graphics.fillRect(x + column, y + row,
                        Math.min(CHECKER, panel - column), Math.min(CHECKER, panel - row));
            }
        }
    }

    /**
     * A refusal, drawn as a refusal. Filling this panel with what the encoder would have produced would
     * be showing a result the policy says not to trust.
     */
    private static void hatch(Graphics2D graphics, int x, int y, int panel, String label) {
        graphics.setColor(new Color(0x1c, 0x1f, 0x25));
        graphics.fillRect(x, y, panel, panel);
        graphics.setColor(new Color(0x2b, 0x30, 0x38));
        for (int offset = -panel; offset < panel; offset += 9) {
            graphics.drawLine(x + offset, y, x + offset + panel, y + panel);
        }
        if (label != null) {
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            graphics.setColor(CAPTION_DIM);
            graphics.drawString(label, x + 8, y + panel / 2);
        }
    }

    private static void blit(BufferedImage sheet, int[] panelPixels, int x, int y, int panel) {
        for (int row = 0; row < panel; row++) {
            for (int column = 0; column < panel; column++) {
                int pixel = panelPixels[row * panel + column];
                int alpha = pixel >>> 24;
                if (alpha == 0) {
                    continue;
                }
                if (alpha == 0xff) {
                    sheet.setRGB(x + column, y + row, pixel);
                    continue;
                }
                sheet.setRGB(x + column, y + row, over(pixel, sheet.getRGB(x + column, y + row)));
            }
        }
    }

    /** Source-over composite, so a sprite's own transparency shows the checkerboard through it. */
    private static int over(int source, int destination) {
        double alpha = (source >>> 24) / 255.0;
        int red = (int) Math.round(((source >> 16) & 0xff) * alpha + ((destination >> 16) & 0xff) * (1 - alpha));
        int green = (int) Math.round(((source >> 8) & 0xff) * alpha + ((destination >> 8) & 0xff) * (1 - alpha));
        int blue = (int) Math.round((source & 0xff) * alpha + (destination & 0xff) * (1 - alpha));
        return (red << 16) | (green << 8) | blue;
    }

    /**
     * Centres an image in a panel, reducing it by area average when it is larger and leaving it alone
     * when it is smaller.
     *
     * <p>Nothing is ever enlarged. Invented pixels in an artifact whose purpose is spotting artifacts
     * would be the wrong kind of help.
     */
    private static int[] fit(int[] argb, int width, int height, int panel) {
        int[] out = new int[panel * panel];
        int targetWidth = Math.min(width, panel);
        int targetHeight = Math.min(height, panel);
        int[] scaled = targetWidth == width && targetHeight == height
                ? argb
                : reduce(argb, width, height, targetWidth, targetHeight);
        int offsetX = (panel - targetWidth) / 2;
        int offsetY = (panel - targetHeight) / 2;
        for (int row = 0; row < targetHeight; row++) {
            System.arraycopy(scaled, row * targetWidth, out, (row + offsetY) * panel + offsetX, targetWidth);
        }
        return out;
    }

    private static int[] reduce(int[] argb, int width, int height, int targetWidth, int targetHeight) {
        ImageResampler.Taps horizontal = ImageResampler.taps(width, targetWidth);
        ImageResampler.Taps vertical = ImageResampler.taps(height, targetHeight);
        int[] out = new int[targetWidth * targetHeight];
        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                double alphaSum = 0;
                double redSum = 0;
                double greenSum = 0;
                double blueSum = 0;
                for (int dy = 0; dy < vertical.weightCount(y); dy++) {
                    int row = (vertical.first(y) + dy) * width;
                    for (int dx = 0; dx < horizontal.weightCount(x); dx++) {
                        double weight = vertical.weight(y, dy) * horizontal.weight(x, dx);
                        int pixel = argb[row + horizontal.first(x) + dx];
                        double alpha = weight * (pixel >>> 24);
                        alphaSum += alpha;
                        redSum += alpha * ((pixel >>> 16) & 0xff);
                        greenSum += alpha * ((pixel >>> 8) & 0xff);
                        blueSum += alpha * (pixel & 0xff);
                    }
                }
                out[y * targetWidth + x] = ImageResampler.pack(alphaSum, redSum, greenSum, blueSum);
            }
        }
        return out;
    }

    /**
     * The error map as an image, reduced by maximum.
     *
     * <p>Averaging here would defeat the panel. A four-pixel encoding artifact on a 2048-square texture
     * survives an average at roughly a 256th of its strength — invisible — and survives a maximum at
     * full strength, which is the entire reason to draw it.
     */
    private static int[] heat(double[] deltaE, int width, int height, int panel) {
        int targetWidth = Math.min(width, panel);
        int targetHeight = Math.min(height, panel);
        ImageResampler.Taps horizontal = ImageResampler.taps(width, targetWidth);
        ImageResampler.Taps vertical = ImageResampler.taps(height, targetHeight);
        int[] out = new int[panel * panel];
        int offsetX = (panel - targetWidth) / 2;
        int offsetY = (panel - targetHeight) / 2;
        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                double worst = 0;
                for (int dy = 0; dy < vertical.weightCount(y); dy++) {
                    int row = (vertical.first(y) + dy) * width;
                    for (int dx = 0; dx < horizontal.weightCount(x); dx++) {
                        worst = Math.max(worst, deltaE[row + horizontal.first(x) + dx]);
                    }
                }
                out[(y + offsetY) * panel + x + offsetX] = 0xff000000 | ramp(worst);
            }
        }
        return out;
    }

    /** Black to red at just-noticeable, red to yellow at obvious. The legend states both. */
    private static int ramp(double deltaE) {
        double t = Math.min(1.0, deltaE / TextureFidelity.OBVIOUS);
        int red = (int) Math.round(Math.min(1.0, t * 2) * 255);
        int green = t <= 0.5 ? 0 : (int) Math.round((t - 0.5) * 2 * 255);
        return (red << 16) | (green << 8);
    }

    private static Map<String, Object> report(
            List<Cell> cells, Path target, BufferedImage sheet, String profileFingerprint, Options options)
            throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sheet", target);
        report.put("sheetPixels", sheet.getWidth() + "x" + sheet.getHeight());
        report.put("sheetBytes", Files.size(target));
        report.put("profileFingerprint", profileFingerprint);
        report.put("codecVersion", (long) BlockCompressor.CODEC_VERSION);
        report.put("fidelityGate", options.maxP99DeltaE());
        report.put("cells", (long) cells.size());
        Map<String, Object> byDisposition = new LinkedHashMap<>();
        for (Disposition disposition : Disposition.values()) {
            long count = cells.stream().filter(cell -> cell.disposition() == disposition).count();
            if (count > 0) {
                byDisposition.put(disposition.name(), count);
            }
        }
        report.put("byDisposition", byDisposition);
        // A digest of the panel data rather than of the file. The PNG also holds captions, and captions
        // are drawn with a platform font, so two machines can produce different bytes from identical
        // findings. This digest covers exactly what the sheet is evidence about.
        report.put("panelsSha256", panelsDigest(cells));
        report.put("index", cells.stream().map(Cell::toMap).toList());
        report.put("audit", "the classifier decides what to encode by filename; check that every cell "
                + "labelled `shader map` depicts one, and that none labelled `cached` does");
        return report;
    }

    private static String panelsDigest(List<Cell> cells) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Cell cell : cells) {
                digest.update(cell.logicalPath().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) cell.disposition().ordinal());
                update(digest, cell.original());
                update(digest, cell.reconstruction());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static void update(MessageDigest digest, int[] pixels) {
        if (pixels == null) {
            digest.update((byte) 0);
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocate(pixels.length * Integer.BYTES);
        buffer.asIntBuffer().put(pixels);
        digest.update(buffer.array());
    }

    /**
     * @param reconstruction null when nothing was encoded, which is a decision rather than a failure
     * @param difference null alongside a null reconstruction
     */
    private record Cell(
            String logicalPath,
            Disposition disposition,
            int width,
            int height,
            String format,
            double meanDeltaE,
            double p99DeltaE,
            int[] original,
            int[] reconstruction,
            double[] difference) {

        static Cell unreadable(String path) {
            return new Cell(path, Disposition.UNREADABLE, 1, 1, "-", 0, 0, new int[] {0}, null, null);
        }

        /** Keeps the filename when a path is too long for the caption; the tail is what identifies it. */
        String shortPath() {
            int limit = 46;
            return logicalPath.length() <= limit
                    ? logicalPath
                    : "..." + logicalPath.substring(logicalPath.length() - limit + 3);
        }

        String detail() {
            String size = width + "x" + height;
            return switch (disposition) {
                case CACHED, OVER_GATE -> String.format(
                        "%s  %s  %s  p99 %.2f  mean %.2f",
                        size, format, disposition.label(), p99DeltaE, meanDeltaE);
                case SHADER_MAP -> size + "  " + disposition.label() + "  not measured";
                case UNREADABLE -> disposition.label();
            };
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("path", logicalPath);
            values.put("disposition", disposition.name());
            values.put("width", (long) width);
            values.put("height", (long) height);
            values.put("format", format);
            if (disposition == Disposition.CACHED || disposition == Disposition.OVER_GATE) {
                values.put("meanDeltaE", Math.round(meanDeltaE * 10000.0) / 10000.0);
                values.put("p99DeltaE", Math.round(p99DeltaE * 10000.0) / 10000.0);
            }
            return values;
        }
    }
}
