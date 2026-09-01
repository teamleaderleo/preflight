package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.resources.LargestAllocations;
import dev.starsector.preflight.core.resources.ModResourceCost;
import dev.starsector.preflight.core.resources.ResourceCostInspector;
import dev.starsector.preflight.core.resources.ResourceCostReport;
import dev.starsector.preflight.core.resources.ResourceCostSummary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Command handler for `preflight inspect resources` and `preflight cost`.
 */
final class ResourceCostCommand {
    private ResourceCostCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        Options options = Options.parse(args, offset);

        Path installRoot = InstallRoot.resolve(options.game());
        if (installRoot == null) {
            System.err.println("Preflight could not locate Starsector. Run `doctor` or provide --game.");
            return 3;
        }

        ResourceCostReport report = ResourceCostInspector.inspect(installRoot, options.modId());

        // Apply mod sorting if requested
        if (options.sort() != null && !report.mods().isEmpty()) {
            List<ModResourceCost> sortedMods = new ArrayList<>(report.mods());
            Comparator<ModResourceCost> comparator = switch (options.sort().toLowerCase(Locale.ROOT)) {
                case "vram" -> Comparator.comparingLong((ModResourceCost m) -> m.texture().residentBytes()).reversed();
                case "pcm" -> Comparator.comparingLong((ModResourceCost m) -> m.audio().effectPcmBytes()).reversed();
                case "bytecode" -> Comparator.comparingLong((ModResourceCost m) -> m.bytecode().uncompressedBytecodeBytes()).reversed();
                case "disk" -> Comparator.comparingLong(ModResourceCost::totalDiskBytes).reversed();
                default -> Comparator.comparingLong(ModResourceCost::estimatedMemoryBytes).reversed();
            };
            sortedMods.sort(comparator);
            report = new ResourceCostReport(
                    report.format(),
                    report.generatedAt(),
                    report.installRoot(),
                    report.profileFingerprint(),
                    report.scanDurationMs(),
                    report.summary(),
                    sortedMods,
                    report.largestAllocations(),
                    report.diagnostics());
        }

        String jsonText = report.toJson();

        if (options.output() != null) {
            Path output = options.output().toAbsolutePath().normalize();
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            Files.writeString(output, jsonText + System.lineSeparator(), StandardCharsets.UTF_8);
        }

        if (options.json()) {
            System.out.println(jsonText);
        } else {
            System.out.print(renderTelemetryTable(report, options.modId()));
        }

        return 0;
    }

    private static String renderTelemetryTable(ResourceCostReport report, String modFilter) {
        StringBuilder sb = new StringBuilder();
        ResourceCostSummary s = report.summary();

        boolean ansi = isAnsiEnabled();
        String bold = ansi ? "\u001B[1m" : "";
        String cyan = ansi ? "\u001B[36m" : "";
        String yellow = ansi ? "\u001B[33m" : "";
        String reset = ansi ? "\u001B[0m" : "";

        sb.append(bold).append("=== PREFLIGHT RESOURCE COST INSPECTOR ===").append(reset).append("\n");
        sb.append("Install:     ").append(report.installRoot()).append("\n");
        sb.append("Profile:     ").append(report.profileFingerprint().substring(0, Math.min(16, report.profileFingerprint().length()))).append("...");
        sb.append(" (").append(s.enabledModCount()).append(" enabled mods, scanned in ").append(String.format(Locale.ROOT, "%.1f ms", report.scanDurationMs())).append(")\n\n");

        sb.append(bold).append("TELEMETRY TOTALS:").append(reset).append("\n");
        sb.append(String.format("  Estimated Total RAM:   %s%s%s (GPU VRAM + Effect PCM + Bytecode)%n",
                cyan, formatBytes(s.totalEstimatedMemoryBytes()), reset));
        sb.append(String.format("  Total Disk Space:      %s%n", formatBytes(s.totalDiskBytes())));

        double vramWastePct = s.textureVram().residentGpuBytes() > 0
                ? (s.textureVram().paddingWasteBytes() * 100.0 / s.textureVram().residentGpuBytes())
                : 0.0;
        String wasteHighlight = vramWastePct > 40.0 ? yellow : "";
        sb.append(String.format("  GPU Texture VRAM:      %s (%d textures, %s%s %.0f%% POT padding waste%s, %s mip ceiling)%n",
                formatBytes(s.textureVram().residentGpuBytes()),
                s.textureVram().textureCount(),
                wasteHighlight,
                formatBytes(s.textureVram().paddingWasteBytes()),
                vramWastePct,
                reset,
                formatBytes(s.textureVram().mipChainUpperBoundBytes())));

        sb.append(String.format("  Audio PCM Memory:      %s (%d effects in RAM, %d streamed music [%s disk], %d unreferenced [%s disk])%n",
                formatBytes(s.audioPcm().effectPcmBytes()),
                s.audioPcm().effectCount(),
                s.audioPcm().musicCount(),
                formatBytes(s.audioPcm().musicDiskBytes()),
                s.audioPcm().unreferencedCount(),
                formatBytes(s.audioPcm().unreferencedDiskBytes())));

        String dupHighlight = s.bytecode().duplicateClasses() > 0 ? yellow : "";
        sb.append(String.format("  Bytecode & Classes:    %s (%d classes in %d JARs, %s%d class collisions%s)%n",
                formatBytes(s.bytecode().uncompressedBytecodeBytes()),
                s.bytecode().classCount(),
                s.bytecode().jarCount(),
                dupHighlight,
                s.bytecode().duplicateClasses(),
                reset));

        long totalPrepared = s.preparedData().preparedTextureBytes()
                + s.preparedData().preparedAudioBytes()
                + s.preparedData().janinoBytecodeBytes()
                + s.preparedData().specCacheBytes();
        sb.append(String.format("  Prepared Data Caches:  %s (%s textures, %s audio, %s Janino, %s specs)%n%n",
                formatBytes(totalPrepared),
                formatBytes(s.preparedData().preparedTextureBytes()),
                formatBytes(s.preparedData().preparedAudioBytes()),
                formatBytes(s.preparedData().janinoBytecodeBytes()),
                formatBytes(s.preparedData().specCacheBytes())));

        sb.append(bold).append(String.format("%-18s | %-10s | %-12s | %-14s | %-10s | %-10s | %-10s | %-10s",
                "MOD ID (ORDER)", "DISK", "VRAM (GPU)", "POT WASTE", "AUDIO PCM", "BYTECODE", "PREPARED", "EST. TOTAL")).append(reset).append("\n");
        sb.append("-".repeat(105)).append("\n");

        for (ModResourceCost m : report.mods()) {
            double modWastePct = m.texture().residentBytes() > 0
                    ? (m.texture().paddingWasteBytes() * 100.0 / m.texture().residentBytes())
                    : 0.0;
            String wasteStr = formatBytes(m.texture().paddingWasteBytes()) + String.format(Locale.ROOT, " %.0f%%", modWastePct);
            String rowMod = m.id() + " (" + m.order() + ")";
            long preparedBytes = m.preparedData().textureCacheBytes() + m.preparedData().audioCacheBytes() + m.preparedData().specCacheBytes();

            sb.append(String.format("%-18s | %-10s | %-12s | %-14s | %-10s | %-10s | %-10s | %-10s%n",
                    truncate(rowMod, 18),
                    formatBytes(m.totalDiskBytes()),
                    formatBytes(m.texture().residentBytes()),
                    truncate(wasteStr, 14),
                    formatBytes(m.audio().effectPcmBytes()),
                    formatBytes(m.bytecode().uncompressedBytecodeBytes()),
                    formatBytes(preparedBytes),
                    formatBytes(m.estimatedMemoryBytes())));
        }

        if (modFilter != null && report.mods().size() == 1) {
            ModResourceCost m = report.mods().get(0);
            sb.append("\n").append(bold).append("MOD DRILLDOWN: ").append(m.name()).append(" (").append(m.id()).append(") v").append(m.version()).append(reset).append("\n");
            if (m.shadowedByOverrides() != null && m.shadowedByOverrides().texturesOverridden() > 0) {
                sb.append(yellow).append("  [!] ").append(m.shadowedByOverrides().texturesOverridden())
                        .append(" textures overridden (shadowed) by higher priority mods (")
                        .append(formatBytes(m.shadowedByOverrides().vramShadowedBytes())).append(" VRAM saved)").append(reset).append("\n");
            }
            if (m.bytecode().duplicateClassCount() > 0) {
                sb.append(yellow).append("  [!] ").append(m.bytecode().duplicateClassCount())
                        .append(" classes collide with other enabled mods").append(reset).append("\n");
            }
        }

        return sb.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
        } else if (bytes >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
        } else if (bytes >= 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        } else {
            return bytes + " B";
        }
    }

    private static String truncate(String text, int length) {
        if (text.length() <= length) return text;
        return text.substring(0, length - 1) + "…";
    }

    private static boolean isAnsiEnabled() {
        return System.console() != null
                && System.getenv("NO_COLOR") == null
                && !"dumb".equals(System.getenv("TERM"));
    }

    private record Options(
            Path game,
            Path launcher,
            String modId,
            String sort,
            boolean json,
            Path output) {

        static Options parse(String[] args, int offset) {
            Path game = null;
            Path launcher = null;
            String modId = null;
            String sort = null;
            boolean json = false;
            Path output = null;

            for (int i = offset; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--game" -> game = Path.of(requireValue(args, ++i, "--game"));
                    case "--launcher" -> launcher = Path.of(requireValue(args, ++i, "--launcher"));
                    case "--mod" -> modId = requireValue(args, ++i, "--mod");
                    case "--sort" -> sort = requireValue(args, ++i, "--sort");
                    case "--json" -> json = true;
                    case "--output" -> output = Path.of(requireValue(args, ++i, "--output"));
                    default -> throw new IllegalArgumentException("Unknown resource inspection option: " + arg);
                }
            }
            return new Options(game, launcher, modId, sort, json, output);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException("Expected value after " + option);
            }
            return args[index];
        }
    }
}
