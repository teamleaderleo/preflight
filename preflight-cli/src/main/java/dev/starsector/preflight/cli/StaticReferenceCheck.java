package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceProviderComparison;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 *
 * <p>Each spec is parsed only from bytes read through the existing exact provider-generation proof.
 * A provider replacement between index construction and analysis therefore becomes non-authoritative
 * instead of letting newer bytes inherit an older profile/index identity.
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
    // SetupAnalysis.Result rejects more than 256 findings. Reserve one slot for deterministic
    // aggregation once detailed missing-reference findings exceed the public model's capacity.
    private static final int MAX_PUBLIC_FINDINGS = 256;
    private static final int MAX_DETAIL_FINDINGS = MAX_PUBLIC_FINDINGS - 1;

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
        int hullInputs = 0;
        int skinInputs = 0;
        int variantInputs = 0;
        int totalConversionSkips = 0;
        int unknownContextReferences = 0;
        boolean hullUniverseComplete = true;

        for (Map.Entry<String, List<ResourceIndex.Provider>> entry : index.entries().entrySet()) {
            String path = entry.getKey();
            boolean skin = isSkin(path);
            boolean hull = isHull(path);
            boolean variant = isVariant(path);
            if (!hull && !skin && !variant) {
                continue;
            }

            // Refuse before opening another winning candidate once a class has exhausted its reviewed
            // work cap. Count attempts, not only successfully decoded specs, so empty/malformed inputs
            // cannot bypass the file-count bound while consuming filesystem/parser work.
            if (skin) {
                if (skinInputs >= MAX_SKINS) {
                    throw new IOException("Static skin analysis exceeds the 4,096-definition limit");
                }
                skinInputs++;
            } else if (hull) {
                if (hullInputs >= MAX_HULLS) {
                    throw new IOException("Static hull analysis exceeds the 4,096-definition limit");
                }
                hullInputs++;
            } else {
                if (variantInputs >= MAX_VARIANTS) {
                    throw new IOException("Static variant analysis exceeds the 16,384-definition limit");
                }
                variantInputs++;
            }

            List<ResourceIndex.Provider> providers = entry.getValue();
            ResourceIndex.Provider winner = providers.get(providers.size() - 1);
            if (winner.size() > MAX_SPEC_BYTES) {
                skipped++;
                if (hull || skin) hullUniverseComplete = false;
                continue;
            }

            long remaining = MAX_TOTAL_SPEC_BYTES - bytes;
            if (remaining <= 0 || winner.size() > remaining) {
                throw new IOException("Static hull/skin/variant analysis exceeds the 32 MiB input budget");
            }
            int readLimit = Math.toIntExact(Math.min((long) MAX_SPEC_BYTES, remaining));
            Text document = exactBoundedText(index, path, winner, readLimit, remaining < MAX_SPEC_BYTES);
            if (document == null) {
                skipped++;
                if (hull || skin) hullUniverseComplete = false;
                continue;
            }
            bytes = Math.addExact(bytes, document.bytes());

            Spec spec;
            try {
                String rawHullId = skin
                        ? JsonText.rootString(document.value(), "skinHullId")
                        : JsonText.rootString(document.value(), "hullId");
                if (rawHullId != null && !rawHullId.isBlank() && rawHullId.length() > MAX_ID_CHARS) {
                    skipped++;
                    if (hull || skin) hullUniverseComplete = false;
                    continue;
                }
                if ((hull || skin) && (rawHullId == null || rawHullId.isBlank())) {
                    skipped++;
                    hullUniverseComplete = false;
                    continue;
                }
                String rawVariantId = variant ? JsonText.rootString(document.value(), "variantId") : null;
                String variantId = rawVariantId != null
                                && !rawVariantId.isBlank()
                                && rawVariantId.length() <= MAX_ID_CHARS
                        ? rawVariantId
                        : null;
                spec = new Spec(path, rootId(index, winner), rawHullId, variantId);
            } catch (RuntimeException malformed) {
                skipped++;
                if (hull || skin) hullUniverseComplete = false;
                continue;
            }
            if (skin) {
                skins.add(spec);
            } else if (hull) {
                hulls.add(spec);
            } else {
                variants.add(spec);
            }
        }

        Set<String> hullIds = new HashSet<>();
        addHullIds(hullIds, hulls);
        addHullIds(hullIds, skins);

        List<SetupAnalysis.Finding> findings = new ArrayList<>();
        int incompleteHullUniverseReferences = 0;
        int suppressedMissingHullFindings = 0;
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
                    if (!hullUniverseComplete) {
                        incompleteHullUniverseReferences++;
                        continue;
                    }
                }
            }

            if (findings.size() >= MAX_DETAIL_FINDINGS) {
                suppressedMissingHullFindings++;
                continue;
            }
            findings.add(missingHullFinding(variant, hullId));
        }

        if (suppressedMissingHullFindings > 0) {
            findings.add(new SetupAnalysis.Finding(
                    "static-reference.variant-missing-hull-overflow",
                    PROVIDER,
                    SetupAnalysis.Severity.BLOCKING,
                    "Additional winning variants reference hulls that are absent from the resolved profile.",
                    Map.of("references", suppressedMissingHullFindings),
                    List.of(),
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
        if (incompleteHullUniverseReferences > 0) {
            findings.add(new SetupAnalysis.Finding(
                    "static-reference.variant-hull-universe-incomplete",
                    PROVIDER,
                    SetupAnalysis.Severity.UNKNOWN,
                    "Some variants reference hull IDs not observed in the authoritative hull set, but one or more winning hull definitions could not be decoded exactly.",
                    Map.of("references", incompleteHullUniverseReferences),
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

    private static SetupAnalysis.Finding missingHullFinding(Spec variant, String hullId) {
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
        return new SetupAnalysis.Finding(
                "static-reference.variant-missing-hull",
                PROVIDER,
                SetupAnalysis.Severity.BLOCKING,
                "A winning variant references a hull that is absent from the resolved profile.",
                parameters,
                affectedModIds,
                List.of());
    }

    /**
     * Uses the same provider-local hard-link proof as exact profile hashing. The supplied hasher
     * captures the bounded bytes while hashing them, so analysis adds no second payload read.
     */
    private static Text exactBoundedText(
            ResourceIndex index,
            String logicalPath,
            ResourceIndex.Provider provider,
            int maxBytes,
            boolean aggregateLimit) throws IOException {
        Text[] captured = new Text[1];
        boolean[] limitExceeded = new boolean[1];
        ResourceProviderComparison.ContentIdentitySource source = ResourceProviderContentIdentity.direct(
                index,
                input -> {
                    byte[] content = input.readNBytes(Math.addExact(maxBytes, 1));
                    if (content.length > maxBytes) {
                        limitExceeded[0] = true;
                        throw new IOException(aggregateLimit
                                ? "Static hull/skin/variant analysis exceeds the 32 MiB input budget"
                                : "Static spec file exceeds the 1 MiB input limit");
                    }
                    captured[0] = new Text(new String(content, StandardCharsets.UTF_8), content.length);
                    return Hashes.sha256(content);
                });
        ResourceProviderComparison.ContentObservation observation = source.observe(logicalPath, provider);
        if (limitExceeded[0]) {
            throw new IOException(aggregateLimit
                    ? "Static hull/skin/variant analysis exceeds the 32 MiB input budget"
                    : "Static spec file exceeds the 1 MiB input limit");
        }
        if (observation.evidence() != ResourceProviderComparison.ContentEvidence.HASHED) {
            return null;
        }
        return captured[0];
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
