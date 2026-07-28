package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.GpuTextureFootprint;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ImageHeaderReader;
import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reports asset problems in a profile, attributed to the mod that ships them.
 *
 * <p>Everything else in this repository works <em>around</em> whatever a profile contains. This
 * points the same analysis at the source instead, because a mod author fixing an asset helps every
 * user of that mod with no cache, no adapter, and no equivalence gate — none of the machinery that
 * exists here only because changing what a running game sees is dangerous.</p>
 *
 * <p>It reads headers and reports. It never edits, moves, or rewrites anything, and it has no opinion
 * about art. Every rule states a measured cost and why it is paid, because the audience is someone
 * who did not ask for this and is entitled to disagree with it.</p>
 *
 * <p>Rules are deliberately few. A tool that tells strangers their work is wrong pays a much higher
 * price for a false positive than for a missing check, so a rule earns its place only when the cost
 * is measurable from the file itself and does not depend on intent.</p>
 */
final class AssetLint {
    /**
     * Above this, a stream carries content no consumer playback path reproduces, and Vorbis decode
     * cost scales with rate. 48 kHz is left alone: it is an ordinary production rate.
     */
    private static final int OVERSAMPLED_HZ = 96_000;
    /** A "fully decoded effect" this long is almost certainly music declared in the wrong section. */
    private static final double LONG_EFFECT_SECONDS = 60;
    /**
     * Below this, padding waste is real but too small to be worth an author's attention. The floor is
     * doing heavy lifting: 84% of mod images are not powers of two, so without it this rule would
     * describe normal practice back at almost every author. Above it, the finding is about a handful
     * of large textures where rounding up costs megabytes.
     */
    private static final long TEXTURE_PADDING_FLOOR_BYTES = 1L << 20;
    /**
     * Duplicate detection hashes only files at least this large. Small identical files are common and
     * uninteresting — a rule that reports five hundred repeated 2 KB icons is noise, not a finding.
     */
    private static final long DUPLICATE_FLOOR_BYTES = 64L << 10;
    private static final int FINDINGS_PER_RULE_LIMIT = 25;

    /**
     * Editor project formats. The game reads none of them, so shipping one costs a user download and
     * disk for a file that never loads. Deliberately excludes {@code .wav}, which looks like an
     * intermediate but is a format Starsector plays — twenty effects in the reviewed profile are
     * declared as {@code .wav}.
     */
    private static final Set<String> EDITOR_SOURCE_EXTENSIONS = Set.of(
            ".pdn", ".psd", ".psb", ".xcf", ".kra", ".aseprite", ".ase", ".clip", ".sai",
            ".afphoto", ".afdesign", ".blend", ".blend1");

    private AssetLint() {
    }

    enum Severity {
        /** The game cannot load this at all. */
        ERROR,
        /** It loads, and costs measurably more than an equivalent asset would. */
        WARNING,
        /** Worth knowing; no runtime cost. */
        INFO
    }

    /**
     * Which resource a finding's bytes are spent in. These are not interchangeable and must never be
     * added together — a megabyte of video memory and a megabyte of disk are different problems, and
     * one headline number combining them would be worse than no number at all.
     */
    enum Cost {
        /** Bytes shipped on disk. */
        DISK("on disk"),
        /** Bytes of PCM the game builds in memory while loading. */
        DECODED("decoded at load"),
        /** Bytes resident in video memory. */
        VRAM("in video memory"),
        /** Nothing measurable; the finding is about correctness. */
        NONE("");

        private final String label;

        Cost(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /** Why a rule exists, stated once for the rule rather than restated from one file's numbers. */
    static String explain(String rule) {
        return switch (rule) {
            case "audio-undecodable" ->
                    "The game decodes only Vorbis in an Ogg container, whatever the file is named. "
                            + "These will not play.";
            case "audio-oversampled" ->
                    "Recorded above 96 kHz. Vorbis decode cost scales with sample rate, so these cost "
                            + "several times the CPU of the same audio at 44.1 kHz, and the content above "
                            + "about 22 kHz is not reproduced by consumer playback.";
            case "audio-long-effect" ->
                    "Declared outside the music section, so the game decodes them in full at load "
                            + "rather than streaming them. Entries in the music section stream instead.";
            case "audio-unreferenced" ->
                    "Shipped but named by no sounds.json in the profile, so nothing loads them through "
                            + "the normal path. They may be played from code, or they may be leftovers.";
            case "texture-npot-padding" ->
                    "Dimensions are not powers of two, so the stock loader uploads them into the next "
                            + "power-of-two buffer and the remainder holds nothing. This is normal and "
                            + "usually right — 84% of mod art is not a power of two, and padding a "
                            + "sprite would be worse. Only textures large enough to waste a megabyte "
                            + "are listed, where choosing dimensions that round up less is worth it.";
            case "sound-declared-missing" ->
                    "Named by a sounds.json but supplied by no mod in the profile, so the game has "
                            + "nothing to play when the sound is triggered.";
            case "asset-shadowed" ->
                    "Another mod later in the load order provides the same path, so this copy is "
                            + "never loaded. Intentional when the override is the point; otherwise one "
                            + "of the two is not having the effect its author expected.";
            case "asset-duplicate-content" ->
                    "Byte-for-byte identical to another file at a different path in the profile. "
                            + "Both are shipped and both occupy memory if both are loaded.";
            case "texture-progressive" ->
                    "Stored for progressive display — progressive mode in JPEG, Adam7 interlacing in "
                            + "PNG. Java's ImageIO, which is 67-70% of what loading a texture costs, "
                            + "decodes these about 8.75x slower than the identical image stored "
                            + "normally. The refinement passes buy nothing for art read from a local "
                            + "disk, and re-saving without them changes no pixels.";
            case "asset-extension-mismatch" ->
                    "The file's contents are a different image format from the one its name claims. "
                            + "Loaders that trust the extension will reject these, and a JPEG named "
                            + ".png carries no alpha channel whatever the name suggests.";
            case "asset-editor-source" ->
                    "An editor project file. The game does not read these formats, so they cost "
                            + "download size and disk without ever loading.";
            default -> "";
        };
    }

    record Finding(
            String rule,
            Severity severity,
            Cost cost,
            String provider,
            String path,
            long bytes,
            String detail) {

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("rule", rule);
            values.put("severity", severity.name().toLowerCase(Locale.ROOT));
            values.put("cost", cost.name().toLowerCase(Locale.ROOT));
            values.put("provider", provider);
            values.put("path", path);
            values.put("bytes", bytes);
            values.put("detail", detail);
            return values;
        }
    }

    record Result(Map<String, Object> report, List<Finding> findings) {
    }

    static Result scan(Path installRoot, String modFilter) throws IOException {
        ResourceIndexBuilder.BuildResult built = ResourceIndexBuilder.build(installRoot);
        return analyse(installRoot, built, modFilter, true);
    }

    /**
     * Lints one mod directory on its own, with no profile around it.
     *
     * <p>Three rules are suppressed rather than answered, because a lone directory cannot answer them
     * honestly. {@code asset-shadowed} compares providers and there is only one. {@code
     * sound-declared-missing} would fire on every core sound a mod legitimately reuses — a mod
     * declaring {@code sounds/sfx_ui/click.ogg} is not shipping a broken reference, it is relying on
     * the base game.</p>
     *
     * <p>{@code audio-unreferenced} is suppressed for the mirror-image reason, found by cross-checking
     * both modes against the reviewed profile: {@code knights_of_ludd} reports sixteen unreferenced
     * sounds alone and <em>none</em> in the profile, because a companion mod's {@code sounds.json}
     * declares them. Declarations are merged across the whole profile, so one directory cannot see
     * who declares its files, and every one of those sixteen would be a false positive shown to an
     * author who did nothing wrong.</p>
     */
    static Result scanStandalone(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        ResourceIndexBuilder.BuildResult built = ResourceIndexBuilder.buildStandalone(root, modId(root));
        return analyse(root, built, null, false);
    }

    private static String modId(Path directory) {
        Path info = directory.resolve("mod_info.json");
        if (java.nio.file.Files.isRegularFile(info)) {
            try {
                String id = JsonText.string(
                        java.nio.file.Files.readString(info, java.nio.charset.StandardCharsets.UTF_8), "id");
                if (id != null && !id.isBlank()) {
                    return id;
                }
            } catch (IOException | RuntimeException ignored) {
                // Fall through to the directory name; a missing id is not worth failing the run over.
            }
        }
        Path name = directory.getFileName();
        return name == null ? "(directory)" : name.toString();
    }

    private static Result analyse(
            Path root,
            ResourceIndexBuilder.BuildResult built,
            String modFilter,
            boolean profileWide) throws IOException {
        ResourceIndex index = built.index();
        List<String> diagnostics = new ArrayList<>(built.diagnostics());

        List<Finding> findings = new ArrayList<>();
        findings.addAll(audioFindings(AudioCensus.scan(root, index, diagnostics), profileWide));
        findings.addAll(textureFindings(index, diagnostics));
        findings.addAll(editorSourceFindings(index));
        findings.addAll(duplicateFindings(index, diagnostics));
        if (profileWide) {
            findings.addAll(shadowedFindings(index));
        }

        if (modFilter != null) {
            String wanted = modFilter;
            findings = new ArrayList<>(findings.stream()
                    .filter(finding -> finding.provider().equals(wanted))
                    .toList());
        }
        findings.sort(Comparator.comparing(Finding::provider)
                .thenComparing(Finding::rule)
                .thenComparing(Finding::path));
        return new Result(
                report(root, index, findings, modFilter, profileWide, diagnostics),
                List.copyOf(findings));
    }

    private static List<Finding> audioFindings(AudioCensus.Result census, boolean profileWide) {
        List<Finding> findings = new ArrayList<>();
        for (String missing : profileWide ? census.missingDeclarations() : List.<String>of()) {
            // Attributed to the profile rather than a mod: the declaration and the absence can live
            // in different mods, and naming one of them would be a guess.
            findings.add(new Finding("sound-declared-missing", Severity.ERROR, Cost.NONE,
                    "(profile)", missing, 0, "declared but supplied by no mod"));
        }
        for (AudioCensus.Sound sound : census.sounds()) {
            if (!sound.decodable()) {
                // Only a declared file is one the game will actually try to open.
                Severity severity = sound.kind() == AudioCensus.Kind.UNREFERENCED
                        ? Severity.INFO
                        : Severity.ERROR;
                findings.add(new Finding("audio-undecodable", severity, Cost.NONE,
                        sound.rootId(), sound.logicalPath(), 0, sound.detail()));
                continue;
            }
            if (sound.sampleRate() >= OVERSAMPLED_HZ) {
                long at44 = Math.round(sound.seconds() * 44_100 * sound.channels() * 2);
                findings.add(new Finding("audio-oversampled", Severity.WARNING, Cost.DECODED,
                        sound.rootId(), sound.logicalPath(), Math.max(0, sound.decodedBytes() - at44),
                        sound.sampleRate() + " Hz, " + megabytes(sound.decodedBytes())
                                + " decoded against " + megabytes(at44) + " at 44.1 kHz"));
            }
            if (sound.kind() == AudioCensus.Kind.EFFECT && sound.seconds() > LONG_EFFECT_SECONDS) {
                findings.add(new Finding("audio-long-effect", Severity.WARNING, Cost.DECODED,
                        sound.rootId(), sound.logicalPath(), sound.decodedBytes(),
                        Math.round(sound.seconds()) + " s, " + megabytes(sound.decodedBytes()) + " decoded"));
            }
            if (profileWide && sound.kind() == AudioCensus.Kind.UNREFERENCED) {
                findings.add(new Finding("audio-unreferenced", Severity.INFO, Cost.DISK,
                        sound.rootId(), sound.logicalPath(), sound.encodedBytes(),
                        megabytes(sound.encodedBytes()) + " on disk"));
            }
        }
        return findings;
    }

    /**
     * The stock texture loader uploads into a power-of-two buffer, so a non-power-of-two source pays
     * for the whole rounded-up rectangle in video memory. {@link GpuTextureFootprint#paddingBytes} is
     * the same calculation the prepared-pixel work measured against a live process.
     */
    private static List<Finding> textureFindings(ResourceIndex index, List<String> diagnostics) {
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<ResourceIndex.Provider>> entry : index.entries().entrySet()) {
            String logicalPath = entry.getKey();
            if (!logicalPath.endsWith(".png") && !logicalPath.endsWith(".jpg")
                    && !logicalPath.endsWith(".jpeg")) {
                continue;
            }
            List<ResourceIndex.Provider> providers = entry.getValue();
            ResourceIndex.Provider winner = providers.get(providers.size() - 1);
            Optional<ImageHeaderReader.ImageDimensions> dimensions;
            Optional<ImageHeaderReader.ImageEncoding> encoding;
            try {
                Path file = index.resolveExisting(winner);
                dimensions = ImageHeaderReader.read(file);
                encoding = ImageHeaderReader.encoding(file);
            } catch (IOException | RuntimeException error) {
                diagnostics.add("Could not read " + logicalPath + ": " + error.getMessage());
                continue;
            }
            String provider = index.roots().get(winner.rootIndex()).id();
            if (encoding.isPresent()) {
                if (encoding.get().progressive()) {
                    findings.add(new Finding("texture-progressive", Severity.WARNING, Cost.NONE,
                            provider, logicalPath, 0,
                            encoding.get().format() == ImageHeaderReader.Format.PNG
                                    ? "Adam7 interlaced"
                                    : "progressive JPEG"));
                }
                ImageHeaderReader.Format declared = logicalPath.endsWith(".png")
                        ? ImageHeaderReader.Format.PNG
                        : ImageHeaderReader.Format.JPEG;
                if (encoding.get().format() != declared) {
                    findings.add(new Finding("asset-extension-mismatch", Severity.INFO, Cost.NONE,
                            provider, logicalPath, 0,
                            "contents are " + encoding.get().format() + ", name says "
                                    + declared.name()));
                }
            }
            if (dimensions.isEmpty()) {
                continue;
            }
            int width = dimensions.get().width();
            int height = dimensions.get().height();
            long padding = GpuTextureFootprint.paddingBytes(width, height);
            if (padding < TEXTURE_PADDING_FLOOR_BYTES) {
                continue;
            }
            findings.add(new Finding("texture-npot-padding", Severity.WARNING, Cost.VRAM,
                    provider, logicalPath, padding,
                    width + "x" + height + " uploaded as " + GpuTextureFootprint.uploadDimension(width)
                            + "x" + GpuTextureFootprint.uploadDimension(height) + ", "
                            + megabytes(padding) + " wasted"));
        }
        return findings;
    }

    /**
     * A copy of a path that a later mod also provides. Attributed to the shadowed provider, since
     * that is the author shipping bytes the game never reads.
     */
    private static List<Finding> shadowedFindings(ResourceIndex index) {
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<ResourceIndex.Provider>> entry : index.entries().entrySet()) {
            List<ResourceIndex.Provider> providers = entry.getValue();
            if (providers.size() < 2) {
                continue;
            }
            String winner = index.roots().get(providers.get(providers.size() - 1).rootIndex()).id();
            for (ResourceIndex.Provider shadowed : providers.subList(0, providers.size() - 1)) {
                // Most shadowing is deliberate, and the profile has 1,841 shadowed copies averaging
                // 15 KB. Reporting all of them would bury every other rule under findings whose cost
                // is negligible and whose intent this cannot read. Only sizeable content survives.
                if (shadowed.size() < DUPLICATE_FLOOR_BYTES) {
                    continue;
                }
                findings.add(new Finding("asset-shadowed", Severity.INFO, Cost.DISK,
                        index.roots().get(shadowed.rootIndex()).id(), entry.getKey(), shadowed.size(),
                        AssetLint.megabytes(shadowed.size()) + " shipped; " + winner + " provides this path"));
            }
        }
        return findings;
    }

    private static List<Finding> editorSourceFindings(ResourceIndex index) {
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<ResourceIndex.Provider>> entry : index.entries().entrySet()) {
            String logicalPath = entry.getKey();
            int dot = logicalPath.lastIndexOf('.');
            if (dot < 0 || !EDITOR_SOURCE_EXTENSIONS.contains(logicalPath.substring(dot))) {
                continue;
            }
            for (ResourceIndex.Provider provider : entry.getValue()) {
                findings.add(new Finding("asset-editor-source", Severity.INFO, Cost.DISK,
                        index.roots().get(provider.rootIndex()).id(), logicalPath, provider.size(),
                        AssetLint.megabytes(provider.size()) + " never loaded"));
            }
        }
        return findings;
    }

    /**
     * Files with identical content at different paths. Hashing every file in a large profile would
     * cost more than the whole rest of the run, so only files that share an exact byte length are
     * candidates — two files cannot be identical at different sizes, and equal sizes are rare enough
     * that the surviving set is small.
     *
     * <p>Paths a later mod shadows are excluded; those are {@code asset-shadowed}, and reporting both
     * would count the same bytes twice under two rules.
     */
    private static List<Finding> duplicateFindings(ResourceIndex index, List<String> diagnostics) {
        Map<Long, List<Map.Entry<String, ResourceIndex.Provider>>> bySize = new LinkedHashMap<>();
        for (Map.Entry<String, List<ResourceIndex.Provider>> entry : index.entries().entrySet()) {
            for (ResourceIndex.Provider provider : entry.getValue()) {
                if (provider.size() >= DUPLICATE_FLOOR_BYTES) {
                    bySize.computeIfAbsent(provider.size(), ignored -> new ArrayList<>())
                            .add(Map.entry(entry.getKey(), provider));
                }
            }
        }

        Map<String, List<Map.Entry<String, ResourceIndex.Provider>>> byContent = new LinkedHashMap<>();
        for (List<Map.Entry<String, ResourceIndex.Provider>> candidates : bySize.values()) {
            if (candidates.size() < 2) {
                continue;
            }
            for (Map.Entry<String, ResourceIndex.Provider> candidate : candidates) {
                try {
                    String digest = Hashes.sha256(index.resolveExisting(candidate.getValue()));
                    byContent.computeIfAbsent(candidate.getValue().size() + ":" + digest,
                            ignored -> new ArrayList<>()).add(candidate);
                } catch (IOException | RuntimeException error) {
                    diagnostics.add("Could not hash " + candidate.getKey() + ": " + error.getMessage());
                }
            }
        }

        List<Finding> findings = new ArrayList<>();
        for (List<Map.Entry<String, ResourceIndex.Provider>> group : byContent.values()) {
            List<String> distinctPaths = group.stream().map(Map.Entry::getKey).distinct().sorted().toList();
            if (distinctPaths.size() < 2) {
                continue;
            }
            for (Map.Entry<String, ResourceIndex.Provider> copy : group) {
                String others = distinctPaths.stream()
                        .filter(path -> !path.equals(copy.getKey()))
                        .findFirst()
                        .orElse("");
                findings.add(new Finding("asset-duplicate-content", Severity.INFO, Cost.DISK,
                        index.roots().get(copy.getValue().rootIndex()).id(), copy.getKey(),
                        // Only the redundant copies are avoidable, so the group's cost is spread
                        // across its members rather than charged in full to each of them.
                        copy.getValue().size() * (group.size() - 1) / group.size(),
                        AssetLint.megabytes(copy.getValue().size()) + ", identical to " + others));
            }
        }
        return findings;
    }

    /** Totals per cost kind. Never a single combined figure — see {@link Cost}. */
    static Map<Cost, Long> byCost(List<Finding> findings) {
        Map<Cost, Long> totals = new LinkedHashMap<>();
        for (Finding finding : findings) {
            if (finding.cost() != Cost.NONE) {
                totals.merge(finding.cost(), finding.bytes(), Long::sum);
            }
        }
        return totals;
    }

    private static Map<String, Object> report(
            Path root,
            ResourceIndex index,
            List<Finding> findings,
            String modFilter,
            boolean profileWide,
            List<String> diagnostics) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("reportFormat", "starsector-preflight-asset-lint-v1");
        values.put("generatedAt", java.time.Instant.now().toString());
        values.put("scope", profileWide ? "profile" : "standalone");
        values.put("root", root.toAbsolutePath().normalize().toString());
        values.put("profileFingerprint", index.profileFingerprint());
        if (!profileWide) {
            // Naming what was not checked keeps a clean standalone report from reading as a stronger
            // result than it is.
            values.put("rulesRequiringAProfile",
                    List.of("asset-shadowed", "sound-declared-missing", "audio-unreferenced"));
        }
        if (modFilter != null) {
            values.put("modFilter", modFilter);
        }
        values.put("findings", findings.size());

        Map<String, Object> bySeverity = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            bySeverity.put(severity.name().toLowerCase(Locale.ROOT),
                    findings.stream().filter(finding -> finding.severity() == severity).count());
        }
        values.put("bySeverity", bySeverity);

        Map<String, Object> costs = new LinkedHashMap<>();
        byCost(findings).forEach((cost, bytes) -> costs.put(cost.name().toLowerCase(Locale.ROOT), bytes));
        values.put("bytesByCost", costs);

        values.put("byRule", groupByRule(findings));
        values.put("byProvider", groupByProvider(findings));

        // Bounded per rule so one mod with hundreds of the same finding cannot bury the rest.
        Map<String, List<Map<String, Object>>> samples = new LinkedHashMap<>();
        for (Finding finding : findings) {
            List<Map<String, Object>> forRule =
                    samples.computeIfAbsent(finding.rule(), ignored -> new ArrayList<>());
            if (forRule.size() < FINDINGS_PER_RULE_LIMIT) {
                forRule.add(finding.toMap());
            }
        }
        values.put("samples", samples);
        values.put("diagnostics", diagnostics);
        return values;
    }

    private static List<Map<String, Object>> groupByRule(List<Finding> findings) {
        Map<String, List<Finding>> grouped = new LinkedHashMap<>();
        for (Finding finding : findings) {
            grouped.computeIfAbsent(finding.rule(), ignored -> new ArrayList<>()).add(finding);
        }
        return grouped.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<Finding>> e) -> e.getValue().size())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> {
                    Finding first = entry.getValue().get(0);
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("rule", entry.getKey());
                    values.put("severity", first.severity().name().toLowerCase(Locale.ROOT));
                    values.put("cost", first.cost().name().toLowerCase(Locale.ROOT));
                    values.put("findings", entry.getValue().size());
                    values.put("bytes", entry.getValue().stream().mapToLong(Finding::bytes).sum());
                    values.put("explanation", explain(entry.getKey()));
                    return values;
                })
                .toList();
    }

    private static List<Map<String, Object>> groupByProvider(List<Finding> findings) {
        Map<String, List<Finding>> grouped = new LinkedHashMap<>();
        for (Finding finding : findings) {
            grouped.computeIfAbsent(finding.provider(), ignored -> new ArrayList<>()).add(finding);
        }
        return grouped.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<Finding>> e) -> e.getValue().size())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("provider", entry.getKey());
                    values.put("findings", entry.getValue().size());
                    Map<String, Object> costs = new LinkedHashMap<>();
                    byCost(entry.getValue()).forEach(
                            (cost, bytes) -> costs.put(cost.name().toLowerCase(Locale.ROOT), bytes));
                    values.put("bytesByCost", costs);
                    return values;
                })
                .toList();
    }

    static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
    }
}
