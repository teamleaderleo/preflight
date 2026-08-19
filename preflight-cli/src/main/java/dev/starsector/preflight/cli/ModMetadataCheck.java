package dev.starsector.preflight.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cheap read-only dependency/metadata readiness pass for the currently enabled mod set. */
final class ModMetadataCheck {
    private static final String PROVIDER = "mod-metadata";
    private static final int MAX_MOD_DIRECTORIES = 1_024;
    private static final int MAX_ENABLED_MODS = 1_024;
    private static final int MAX_DEPENDENCIES_PER_MOD = 256;
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final long MAX_TOTAL_METADATA_BYTES = 64L * 1024 * 1024;
    private static final int READ_BUFFER_BYTES = 8 * 1024;

    private ModMetadataCheck() {
    }

    static Result check(Path installRoot) throws IOException {
        return check(installRoot, MAX_TOTAL_METADATA_BYTES);
    }

    static Result check(Path installRoot, long maximumTotalMetadataBytes) throws IOException {
        if (maximumTotalMetadataBytes <= 0 || maximumTotalMetadataBytes > MAX_TOTAL_METADATA_BYTES) {
            throw new IllegalArgumentException("Metadata check budget is outside the supported range");
        }
        long started = System.nanoTime();
        GameLayout layout = GameLayout.locate(installRoot);
        MetadataBudget budget = new MetadataBudget(maximumTotalMetadataBytes);
        Text enabledDocument = boundedText(layout.enabledModsFile(), MAX_METADATA_BYTES, budget);
        JsonText.ArrayRead enabledRead =
                JsonText.stringArrayStatus(enabledDocument.value(), "enabledMods", MAX_ENABLED_MODS);
        if (enabledRead.malformed()) {
            throw new IOException("enabledMods is present but is not a valid string array");
        }
        if (enabledRead.tooMany()) {
            throw new IOException("enabledMods exceeds the bounded mod-count limit");
        }
        List<String> enabled = enabledRead.values();
        Set<String> enabledSet = new LinkedHashSet<>(enabled);

        List<Path> directories;
        try (var stream = Files.list(layout.modsDirectory())) {
            // Read-only analysis follows an immediate directory link just like Starsector can. Only
            // the small metadata file is opened below, with its own hard byte ceiling.
            directories = stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit(MAX_MOD_DIRECTORIES + 1L)
                    .toList();
        }
        if (directories.size() > MAX_MOD_DIRECTORIES) {
            throw new IOException("Too many immediate mod directories for the bounded metadata check");
        }

        Map<String, List<Mod>> byId = new HashMap<>();
        Map<String, MetadataProblem> unresolvedByFolder = new HashMap<>();
        for (Path directory : directories) {
            Path info = directory.resolve("mod_info.json");
            String folder = directory.getFileName().toString();
            if (!Files.isRegularFile(info, LinkOption.NOFOLLOW_LINKS)) {
                unresolvedByFolder.put(folder, MetadataProblem.MISSING);
                continue;
            }
            Text document;
            try {
                document = boundedText(info, MAX_METADATA_BYTES, budget);
            } catch (AggregateBudgetExceededException exhausted) {
                throw exhausted;
            } catch (IOException unreadable) {
                unresolvedByFolder.put(folder, MetadataProblem.UNREADABLE);
                continue;
            }
            String id;
            try {
                id = JsonText.string(document.value(), "id");
            } catch (RuntimeException malformed) {
                unresolvedByFolder.put(folder, MetadataProblem.MALFORMED);
                continue;
            }
            if (id == null || id.isBlank()) {
                unresolvedByFolder.put(folder, MetadataProblem.MALFORMED);
                continue;
            }

            JsonText.ArrayRead dependencyRead = JsonText.objectArrayStatus(
                    document.value(), "dependencies", MAX_DEPENDENCIES_PER_MOD);
            List<String> dependencies = new ArrayList<>();
            boolean dependenciesMalformed = dependencyRead.malformed() || dependencyRead.tooMany();
            for (String dependency : dependencyRead.values()) {
                String dependencyId;
                try {
                    dependencyId = JsonText.string(dependency, "id");
                } catch (RuntimeException malformed) {
                    dependenciesMalformed = true;
                    continue;
                }
                if (dependencyId == null || dependencyId.isBlank()) {
                    dependenciesMalformed = true;
                    continue;
                }
                dependencies.add(dependencyId);
            }

            ConversionMode conversionMode;
            try {
                Boolean totalConversion = JsonText.booleanLike(document.value(), "totalConversion");
                conversionMode = Boolean.TRUE.equals(totalConversion)
                        ? ConversionMode.TOTAL_CONVERSION
                        : ConversionMode.NORMAL;
            } catch (RuntimeException malformed) {
                conversionMode = ConversionMode.UNKNOWN;
            }

            Mod mod = new Mod(id, List.copyOf(dependencies), dependenciesMalformed, conversionMode);
            byId.computeIfAbsent(id, ignored -> new ArrayList<>()).add(mod);
        }

        List<SetupAnalysis.Finding> findings = new ArrayList<>();
        Set<String> relevantIds = new LinkedHashSet<>(enabledSet);
        for (String id : enabled) {
            List<Mod> candidates = byId.getOrDefault(id, List.of());
            if (candidates.isEmpty()) {
                MetadataProblem problem = unresolvedByFolder.get(id);
                if (problem != null) {
                    findings.add(finding(
                            "mod-metadata.enabled-metadata-invalid",
                            SetupAnalysis.Severity.BLOCKING,
                            "Enabled mod " + id + " has unavailable or invalid metadata.",
                            Map.of("modId", id, "problem", problem.code()),
                            List.of(id)));
                } else {
                    findings.add(finding(
                            "mod-metadata.enabled-mod-missing",
                            SetupAnalysis.Severity.BLOCKING,
                            "Enabled mod " + id + " is not installed with readable metadata.",
                            Map.of("modId", id),
                            List.of(id)));
                }
                continue;
            }
            if (candidates.size() != 1) {
                continue;
            }
            Mod mod = candidates.get(0);
            relevantIds.addAll(mod.dependencies());
            if (mod.dependenciesMalformed()) {
                findings.add(finding(
                        "mod-metadata.dependencies-malformed",
                        SetupAnalysis.Severity.WARNING,
                        "Dependency metadata for " + id + " is malformed or incomplete.",
                        Map.of("modId", id),
                        List.of(id)));
            }
            if (mod.conversionMode() == ConversionMode.UNKNOWN) {
                findings.add(finding(
                        "mod-metadata.total-conversion-flag-malformed",
                        SetupAnalysis.Severity.WARNING,
                        "Total-conversion metadata for " + id + " is malformed or incomplete.",
                        Map.of("modId", id),
                        List.of(id)));
            }
        }

        for (String id : relevantIds) {
            List<Mod> candidates = byId.getOrDefault(id, List.of());
            if (candidates.size() > 1) {
                findings.add(finding(
                        "mod-metadata.duplicate-id",
                        SetupAnalysis.Severity.BLOCKING,
                        "Multiple installed mods declare the same ID " + id + ".",
                        Map.of("modId", id, "candidates", candidates.size()),
                        List.of(id)));
            }
        }

        Set<String> dependencyPairs = new LinkedHashSet<>();
        for (String id : enabled) {
            List<Mod> candidates = byId.getOrDefault(id, List.of());
            if (candidates.size() != 1) {
                continue;
            }
            for (String dependencyId : candidates.get(0).dependencies()) {
                if (!dependencyPairs.add(id + "\u0000" + dependencyId)) {
                    continue;
                }
                List<Mod> dependencies = byId.getOrDefault(dependencyId, List.of());
                if (dependencies.isEmpty()) {
                    MetadataProblem problem = unresolvedByFolder.get(dependencyId);
                    if (problem != null) {
                        findings.add(finding(
                                "mod-metadata.required-dependency-invalid",
                                SetupAnalysis.Severity.BLOCKING,
                                "Mod " + id + " requires " + dependencyId
                                        + ", whose installed metadata is unavailable or invalid.",
                                Map.of(
                                        "modId", id,
                                        "dependencyId", dependencyId,
                                        "problem", problem.code()),
                                List.of(id, dependencyId)));
                    } else {
                        findings.add(finding(
                                "mod-metadata.required-dependency-missing",
                                SetupAnalysis.Severity.BLOCKING,
                                "Mod " + id + " requires missing dependency " + dependencyId + ".",
                                Map.of("modId", id, "dependencyId", dependencyId),
                                List.of(id, dependencyId)));
                    }
                    continue;
                }
                if (dependencies.size() > 1) {
                    continue;
                }
                if (!enabledSet.contains(dependencyId)) {
                    findings.add(finding(
                            "mod-metadata.required-dependency-disabled",
                            SetupAnalysis.Severity.BLOCKING,
                            "Mod " + id + " requires installed but disabled dependency " + dependencyId + ".",
                            Map.of("modId", id, "dependencyId", dependencyId),
                            List.of(id, dependencyId)));
                }
            }
        }

        return new Result(
                List.copyOf(findings),
                profileConversionMode(enabled, byId),
                directories.size(),
                budget.bytesRead(),
                System.nanoTime() - started);
    }

    private static ConversionMode profileConversionMode(List<String> enabled, Map<String, List<Mod>> byId) {
        boolean unknown = false;
        for (String id : enabled) {
            List<Mod> candidates = byId.getOrDefault(id, List.of());
            if (candidates.size() != 1) {
                unknown = true;
                continue;
            }
            ConversionMode mode = candidates.get(0).conversionMode();
            if (mode == ConversionMode.TOTAL_CONVERSION) {
                return ConversionMode.TOTAL_CONVERSION;
            }
            if (mode == ConversionMode.UNKNOWN) {
                unknown = true;
            }
        }
        return unknown ? ConversionMode.UNKNOWN : ConversionMode.NORMAL;
    }

    private static SetupAnalysis.Finding finding(
            String code,
            SetupAnalysis.Severity severity,
            String summary,
            Map<String, Object> parameters,
            List<String> affectedModIds) {
        return new SetupAnalysis.Finding(
                code,
                PROVIDER,
                severity,
                summary,
                parameters,
                affectedModIds,
                List.of());
    }

    private static Text boundedText(Path file, int maximumBytes, MetadataBudget budget) throws IOException {
        try (InputStream input = Files.newInputStream(
                file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 64 * 1024));
            byte[] buffer = new byte[READ_BUFFER_BYTES];
            while (true) {
                long remainingAggregate = budget.remainingBytes();
                int remainingFile = maximumBytes - output.size();
                long probe = Math.min((long) remainingFile + 1L, remainingAggregate + 1L);
                int allowed = (int) Math.min(buffer.length, Math.max(1L, probe));
                int read = input.read(buffer, 0, allowed);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                budget.charge(read);
                if ((long) output.size() + read > maximumBytes) {
                    throw new IOException("Metadata file exceeds the " + maximumBytes + " byte limit");
                }
                output.write(buffer, 0, read);
            }
            byte[] bytes = output.toByteArray();
            return new Text(new String(bytes, StandardCharsets.UTF_8), bytes.length);
        }
    }

    record Result(
            List<SetupAnalysis.Finding> findings,
            ConversionMode conversionMode,
            int modDirectories,
            long metadataBytes,
            long elapsedNanos) {
    }

    enum ConversionMode {
        NORMAL,
        TOTAL_CONVERSION,
        UNKNOWN
    }

    private record Text(String value, int bytes) {
    }

    private record Mod(
            String id,
            List<String> dependencies,
            boolean dependenciesMalformed,
            ConversionMode conversionMode) {
    }

    private static final class MetadataBudget {
        private final long maximumBytes;
        private long bytesRead;

        MetadataBudget(long maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        long remainingBytes() {
            return Math.max(0L, maximumBytes - bytesRead);
        }

        long bytesRead() {
            return bytesRead;
        }

        void charge(int bytes) throws AggregateBudgetExceededException {
            if (bytes < 0) {
                throw new IllegalArgumentException("Read byte count may not be negative");
            }
            long next = bytesRead + bytes;
            bytesRead = next;
            if (next > maximumBytes) {
                throw new AggregateBudgetExceededException();
            }
        }
    }

    private static final class AggregateBudgetExceededException extends IOException {
        AggregateBudgetExceededException() {
            super("Aggregate mod metadata exceeds the bounded check budget");
        }
    }

    private enum MetadataProblem {
        MISSING("missing"),
        UNREADABLE("unreadable"),
        MALFORMED("malformed");

        private final String code;

        MetadataProblem(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
