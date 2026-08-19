package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded static cross-reference checks over an already-resolved resource index.
 *
 * <p>This first #252 slice deliberately proves one high-value linker rule only: a winning variant's
 * declared {@code hullId} must name a winning ship hull spec in the same resolved profile. Hull
 * specs can come from ordinary {@code .ship} definitions or {@code .skin} definitions. It does not
 * build another profile index, does not mutate anything, and ignores overridden losing files.
 */
final class StaticReferenceCheck {
    private static final String PROVIDER = "static-links";
    private static final String HULL_PREFIX = "data/hulls/";
    private static final String SKIN_PREFIX = "data/hulls/skins/";
    private static final String VARIANT_PREFIX = "data/variants/";
    private static final int MAX_SPEC_BYTES = 1024 * 1024;
    private static final long MAX_TOTAL_SPEC_BYTES = 32L * 1024 * 1024;
    private static final int MAX_HULLS = 4_096;
    private static final int MAX_SKINS = 4_096;
    private static final int MAX_VARIANTS = 16_384;
    private static final int MAX_ID_CHARS = 256;
    private static final int MAX_PUBLIC_PATH_CHARS = 1_024;

    private StaticReferenceCheck() {
    }

    static Result checkVariantHullLinks(
            ResourceIndex index,
            ModMetadataCheck.ConversionMode conversionMode) throws IOException {
        if (conversionMode == null) {
            throw new IllegalArgumentException("Static reference checks require conversion-mode evidence");
        }
        long started = System.nanoTime();
        List<Spec> hulls = new ArrayList<>();
        List<Spec> skins = new ArrayList<>();
        List<Spec> variants = new ArrayList<>();
        long bytes = 0;
        int skipped = 0;
        int totalConversionSkips = 0;
        int unknownContextReferences = 0;

        for (Map.Entry<String, List<ResourceIndex.Provider>> entry : index.entries().entrySet()) {
            String path = entry.getKey();
            boolean skin = isSkin(path);
            boolean hull = isHull(path);
            boolean variant = isVariant(path);
            if (!hull && !skin && !variant) {
                continue;
            }

            List<ResourceIndex.Provider> providers = entry.getValue();
            ResourceIndex.Provider winner = providers.get(providers.size() - 1);
            if (winner.size() > MAX_SPEC_BYTES) {
                skipped++;
                continue;
            }
            Text document;
            try {
                document = boundedText(index.resolveExisting(winner));
            } catch (IOException unreadable) {
                skipped++;
                continue;
            }
            bytes = Math.addExact(bytes, document.bytes());
            if (bytes > MAX_TOTAL_SPEC_BYTES) {
                throw new IOException("Static hull/skin/variant analysis exceeds the 32 MiB input budget");
            }

            Spec spec;
            try {
                String rawHullId = skin
                        ? JsonText.string(document.value(), "skinHullId")
                        : JsonText.string(document.value(), "hullId");
                if (rawHullId != null && !rawHullId.isBlank() && rawHullId.length() > MAX_ID_CHARS) {
                    skipped++;
                    continue;
                }
                String rawVariantId = variant ? JsonText.string(document.value(), "variantId") : null;
                String variantId = rawVariantId != null
                                && !rawVariantId.isBlank()
                                && rawVariantId.length() <= MAX_ID_CHARS
                        ? rawVariantId
                        : null;
                spec = new Spec(path, rootId(index, winner), rawHullId, variantId);
            } catch (RuntimeException malformed) {
                skipped++;
                continue;
            }
            if (skin) {
                if (skins.size() >= MAX_SKINS) {
                    throw new IOException("Static skin analysis exceeds the 4,096-definition limit");
                }
                skins.add(spec);
            } else if (hull) {
                if (hulls.size() >= MAX_HULLS) {
                    throw new IOException("Static hull analysis exceeds the 4,096-definition limit");
                }
                hulls.add(spec);
            } else {
                if (variants.size() >= MAX_VARIANTS) {
                    throw new IOException("Static variant analysis exceeds the 16,384-definition limit");
                }
                variants.add(spec);
            }
        }

        Set<String> hullIds = new HashSet<>();
        addHullIds(hullIds, hulls);
        addHullIds(hullIds, skins);

        List<SetupAnalysis.Finding> findings = new ArrayList<>();
        for (Spec variant : variants) {
            String hullId = variant.hullId();
            if (hullId == null || hullId.isBlank() || hullIds.contains(hullId)) {
                continue;
            }
            switch (conversionMode) {
                case TOTAL_CONVERSION -> {
                    totalConversionSkips++;
                    continue;
                }
                case UNKNOWN -> {
                    unknownContextReferences++;
                    continue;
                }
                case NORMAL -> {
                    // Ordinary profiles treat a decoded missing hull reference as a static problem.
                }
            }

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("hullId", hullId);
            if (variant.logicalPath().length() <= MAX_PUBLIC_PATH_CHARS) {
                parameters.put("sourcePath", variant.logicalPath());
            }
            if (variant.variantId() != null) {
                parameters.put("variantId", variant.variantId());
            }
            List<String> affectedModIds = variant.rootId().length() <= MAX_ID_CHARS
                    ? List.of(variant.rootId())
                    : List.of();
            findings.add(new SetupAnalysis.Finding(
                    "static-reference.variant-missing-hull",
                    PROVIDER,
                    SetupAnalysis.Severity.BLOCKING,
                    "A winning variant references a hull that is absent from the resolved profile.",
                    parameters,
                    affectedModIds,
                    List.of()));
        }

        if (unknownContextReferences > 0) {
            findings.add(new SetupAnalysis.Finding(
                    "static-reference.variant-hull-context-unknown",
                    PROVIDER,
                    SetupAnalysis.Severity.UNKNOWN,
                    "Some variants reference absent hull IDs, but total-conversion metadata is not authoritative.",
                    Map.of("references", unknownContextReferences),
                    List.of(),
                    List.of()));
        }

        return new Result(
                List.copyOf(findings),
                hulls.size(),
                skins.size(),
                variants.size(),
                skipped,
                totalConversionSkips,
                unknownContextReferences,
                bytes,
                System.nanoTime() - started);
    }

    private static void addHullIds(Set<String> hullIds, List<Spec> specs) {
        for (Spec spec : specs) {
            if (spec.hullId() != null && !spec.hullId().isBlank()) {
                hullIds.add(spec.hullId());
            }
        }
    }

    private static String rootId(ResourceIndex index, ResourceIndex.Provider provider) {
        return index.roots().get(provider.rootIndex()).id();
    }

    private static boolean isHull(String path) {
        return path.startsWith(HULL_PREFIX) && !path.startsWith(SKIN_PREFIX) && path.endsWith(".ship");
    }

    private static boolean isSkin(String path) {
        return path.startsWith(SKIN_PREFIX) && path.endsWith(".skin");
    }

    private static boolean isVariant(String path) {
        return path.startsWith(VARIANT_PREFIX) && path.endsWith(".variant");
    }

    private static Text boundedText(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
            byte[] bytes = input.readNBytes(MAX_SPEC_BYTES + 1);
            if (bytes.length > MAX_SPEC_BYTES) {
                throw new IOException("Static spec file exceeds the 1 MiB input limit");
            }
            return new Text(new String(bytes, StandardCharsets.UTF_8), bytes.length);
        }
    }

    record Result(
            List<SetupAnalysis.Finding> findings,
            int hulls,
            int skins,
            int variants,
            int skipped,
            int totalConversionSkips,
            int unknownContextReferences,
            long bytes,
            long elapsedNanos) {
    }

    private record Text(String value, int bytes) {
    }

    private record Spec(String logicalPath, String rootId, String hullId, String variantId) {
    }
}
