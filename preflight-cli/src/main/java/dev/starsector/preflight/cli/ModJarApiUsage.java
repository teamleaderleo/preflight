package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only static evidence for which enabled mod JARs reference classes supplied by other enabled
 * mods or by the public Starsector API. This deliberately reports relationships, not compatibility
 * verdicts: an undeclared edge may be an intentional optional integration and one launch may take a
 * different dynamic path than the bytecode makes possible.
 */
final class ModJarApiUsage {
    private static final int MAX_JARS = 512;
    private static final int MAX_CLASSFILES_PER_JAR = 50_000;
    private static final int MAX_CLASSFILE_BYTES = 4 * 1024 * 1024;
    private static final long MAX_CLASS_BYTES_PER_JAR = 128L * 1024 * 1024;
    private static final long MAX_CLASS_BYTES_TOTAL = 512L * 1024 * 1024;
    private static final int MAX_REFERENCES_PER_JAR = 100_000;
    private static final int MAX_VERIFIED_CLASSES = 250_000;
    private static final int MAX_STATIC_EDGES = 4_096;
    private static final int MAX_EDGE_CLASSES = 4_096;
    private static final int MAX_STARSECTOR_API_CLASSES_PER_MOD = 4_096;
    private static final int MAX_ERROR_SAMPLES_PER_JAR = 8;
    private static final int MAX_AMBIGUOUS_REFERENCES = 4_096;
    private static final int MAX_AMBIGUOUS_SAMPLES = 128;
    private static final int MAX_INCOMPLETE_PROVIDER_SAMPLES = 128;
    private static final String STARSECTOR_API_PREFIX = "com.fs.starfarer.api.";

    private ModJarApiUsage() {
    }

    static Result scan(Path installRoot) throws IOException {
        return scan(ClasspathAudit.scan(installRoot));
    }

    static Result scan(ClasspathAudit.Result classpath) {
        return scan(classpath, MAX_VERIFIED_CLASSES);
    }

    static Result scan(ClasspathAudit.Result classpath, int maximumVerifiedClasses) {
        if (maximumVerifiedClasses < 1 || maximumVerifiedClasses > MAX_VERIFIED_CLASSES) {
            throw new IllegalArgumentException("verified-class provider limit is invalid: " + maximumVerifiedClasses);
        }
        Inputs inputs = Inputs.from(classpath.values());

        List<JarEvidence> evidence = new ArrayList<>();
        long remainingGlobalBytes = MAX_CLASS_BYTES_TOTAL;
        boolean jarsTruncated = inputs.jars().size() > MAX_JARS;
        for (JarInput jar : inputs.jars().stream().limit(MAX_JARS).toList()) {
            if (remainingGlobalBytes <= 0) {
                jarsTruncated = true;
                break;
            }
            JarEvidence scanned = scanJar(jar, remainingGlobalBytes);
            evidence.add(scanned);
            remainingGlobalBytes -= scanned.classBytesRead();
        }

        Map<String, Boolean> staticScanComplete = staticScanCompleteness(inputs, evidence);
        ProviderIndex providers = ProviderIndex.from(evidence, maximumVerifiedClasses);
        boolean providerInventoryComplete = inputs.jarInventoryComplete()
                && !jarsTruncated
                && evidence.size() == inputs.jars().size()
                && evidence.stream().allMatch(JarEvidence::complete)
                && !providers.truncated();
        RelationshipSummary relationships = relationships(
                inputs, evidence, providers, providerInventoryComplete, staticScanComplete);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("enabledMods", inputs.dependenciesByMod().size());
        totals.put("auditedJars", inputs.auditedJarCount());
        totals.put("candidateJars", inputs.jars().size());
        totals.put("invalidAuditedJars", inputs.invalidAuditedJarCount());
        totals.put("scannedJars", evidence.size());
        totals.put("scannedClassFiles", evidence.stream().mapToLong(JarEvidence::scannedClassFiles).sum());
        totals.put("unreadableClassFiles", evidence.stream().mapToLong(JarEvidence::unreadableClassFiles).sum());
        totals.put("verifiedClassNames", providers.verifiedClassCount());
        totals.put("classBytesRead", evidence.stream().mapToLong(JarEvidence::classBytesRead).sum());
        totals.put("providerInventoryComplete", providerInventoryComplete);
        totals.put("staticModEdges", relationships.edges().size());
        totals.put("undeclaredStaticModEdges", relationships.edges().stream()
                .filter(edge -> !edge.declaredDependency())
                .count());
        totals.put("modsReferencingStarsectorApi", relationships.starsectorApi().size());
        totals.put("ambiguousStaticReferences", relationships.ambiguousCount());
        totals.put("incompleteProviderResolutionReferences", relationships.incompleteProviderCount());
        totals.put("truncated", jarsTruncated
                || providers.truncated()
                || evidence.stream().anyMatch(JarEvidence::truncated)
                || relationships.truncated());

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("evidenceKind", "classfile-static-type-reference-v1");
        values.put("archiveFingerprint", classpath.values().get("archiveFingerprint"));
        values.put("classpathFingerprint", classpath.values().get("classpathFingerprint"));
        values.put("totals", totals);
        values.put("modStaticCompleteness", staticScanComplete.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "modId", entry.getKey(),
                        "staticScanComplete", entry.getValue()))
                .toList());
        values.put("staticModReferences", relationships.edges().stream().map(Edge::toMap).toList());
        values.put("starsectorApiUsage", relationships.starsectorApi().stream().map(ApiUsage::toMap).toList());
        values.put("ambiguousStaticReferenceSamples", relationships.ambiguousSamples());
        values.put("incompleteProviderResolutionSamples", relationships.incompleteProviderSamples());
        values.put("jarScans", evidence.stream().map(JarEvidence::toMap).toList());
        values.put("notes", List.of(
                "Static bytecode evidence only; no mod classes were loaded or executed.",
                "Class references include JVM class constants and referenced/declaration type descriptors.",
                "Reflective class-name strings and generic-signature-only types are not included in this evidence kind.",
                "A static reference does not by itself prove that the code path runs in a given session.",
                "An undeclared cross-mod reference may be an intentional optional integration, not a defect.",
                "Unique cross-mod provider edges are emitted only when providerInventoryComplete is true.",
                "Positive Starsector API references remain observed lower-bound evidence when staticScanComplete is false.",
                "Counts with truncated=true are lower bounds."));
        return new Result(values);
    }

    private static Map<String, Boolean> staticScanCompleteness(
            Inputs inputs, List<JarEvidence> evidence) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        Map<String, Integer> scanned = new LinkedHashMap<>();
        Map<String, Boolean> complete = new LinkedHashMap<>();
        for (String modId : inputs.dependenciesByMod().keySet()) {
            expected.put(modId, 0);
            scanned.put(modId, 0);
            complete.put(modId, !inputs.unresolvedMods().contains(modId)
                    && !inputs.invalidJarMods().contains(modId));
        }
        for (JarInput jar : inputs.jars()) {
            expected.merge(jar.modId(), 1, Integer::sum);
        }
        for (JarEvidence jar : evidence) {
            scanned.merge(jar.modId(), 1, Integer::sum);
            if (!jar.complete()) {
                complete.put(jar.modId(), false);
            }
        }
        for (String modId : complete.keySet()) {
            if (!expected.getOrDefault(modId, 0).equals(scanned.getOrDefault(modId, 0))) {
                complete.put(modId, false);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(complete));
    }

    private static JarEvidence scanJar(JarInput jar, long globalBudget) {
        Path file = jar.directory().resolve(jar.relativePath()).normalize();
        if (!file.startsWith(jar.directory().normalize())) {
            return JarEvidence.failure(jar, "JAR path escaped its mod directory");
        }

        long jarBudget = Math.min(MAX_CLASS_BYTES_PER_JAR, globalBudget);
        long bytesRead = 0;
        long scannedClasses = 0;
        long unreadableClasses = 0;
        boolean truncated = false;
        LinkedHashSet<String> verifiedClasses = new LinkedHashSet<>();
        LinkedHashSet<String> references = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();

        try (ZipFile zip = new ZipFile(file.toFile())) {
            List<? extends ZipEntry> classEntries = zip.stream()
                    .filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".class"))
                    .limit(MAX_CLASSFILES_PER_JAR + 1L)
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            if (classEntries.size() > MAX_CLASSFILES_PER_JAR) {
                truncated = true;
                classEntries = classEntries.subList(0, MAX_CLASSFILES_PER_JAR);
            }

            for (ZipEntry entry : classEntries) {
                if (bytesRead >= jarBudget || references.size() >= MAX_REFERENCES_PER_JAR) {
                    truncated = true;
                    break;
                }
                long declaredSize = entry.getSize();
                if (declaredSize > MAX_CLASSFILE_BYTES) {
                    unreadableClasses++;
                    sample(errors, entry.getName() + ": classfile exceeds " + MAX_CLASSFILE_BYTES + " bytes");
                    continue;
                }
                long remaining = jarBudget - bytesRead;
                if (declaredSize >= 0 && declaredSize > remaining) {
                    truncated = true;
                    break;
                }
                int payloadLimit = (int) Math.min(MAX_CLASSFILE_BYTES, remaining);
                if (payloadLimit <= 0) {
                    truncated = true;
                    break;
                }

                byte[] classfile;
                try (InputStream input = zip.getInputStream(entry)) {
                    classfile = readAtMost(input, payloadLimit + 1);
                } catch (IOException error) {
                    unreadableClasses++;
                    sample(errors, entry.getName() + ": could not read class entry");
                    continue;
                }
                bytesRead += classfile.length;
                if (classfile.length > payloadLimit) {
                    unreadableClasses++;
                    truncated = true;
                    sample(errors, entry.getName() + ": classfile exceeded remaining read budget");
                    break;
                }

                try {
                    JvmClassReferences.Result parsed = JvmClassReferences.parse(classfile);
                    String entryBinaryName = entry.getName()
                            .substring(0, entry.getName().length() - ".class".length())
                            .replace('/', '.');
                    if (!entryBinaryName.equals(parsed.binaryName())) {
                        unreadableClasses++;
                        sample(errors, entry.getName() + ": entry name does not match class identity "
                                + parsed.binaryName());
                        continue;
                    }
                    scannedClasses++;
                    verifiedClasses.add(parsed.binaryName());
                    for (String reference : parsed.references()) {
                        if (references.size() >= MAX_REFERENCES_PER_JAR) {
                            truncated = true;
                            break;
                        }
                        references.add(reference);
                    }
                } catch (IOException error) {
                    unreadableClasses++;
                    sample(errors, entry.getName() + ": " + error.getMessage());
                }
            }
        } catch (IOException error) {
            return new JarEvidence(
                    jar.modId(), jar.relativePath(), 0, 0, bytesRead, true,
                    Set.of(), Set.of(), List.of("Could not read JAR archive"));
        }

        return new JarEvidence(
                jar.modId(), jar.relativePath(), scannedClasses, unreadableClasses, bytesRead, truncated,
                Collections.unmodifiableSet(verifiedClasses), Collections.unmodifiableSet(references), List.copyOf(errors));
    }

    private static byte[] readAtMost(InputStream input, int limit) throws IOException {
        if (limit < 1) {
            return new byte[0];
        }
        return input.readNBytes(limit);
    }

    private static RelationshipSummary relationships(
            Inputs inputs,
            List<JarEvidence> evidence,
            ProviderIndex providerIndex,
            boolean providerInventoryComplete,
            Map<String, Boolean> staticScanComplete) {
        Map<EdgeKey, BoundedNames> edges = new TreeMap<>();
        Map<String, BoundedNames> starsectorApi = new TreeMap<>();
        List<Map<String, Object>> ambiguousSamples = new ArrayList<>();
        Set<String> seenAmbiguous = new LinkedHashSet<>();
        List<Map<String, Object>> incompleteProviderSamples = new ArrayList<>();
        Set<String> seenIncomplete = new LinkedHashSet<>();
        long ambiguousCount = 0;
        long incompleteProviderCount = 0;
        boolean truncated = false;

        for (JarEvidence jar : evidence) {
            for (String reference : jar.references()) {
                if (reference.startsWith(STARSECTOR_API_PREFIX)) {
                    BoundedNames names = starsectorApi.computeIfAbsent(
                            jar.modId(), ignored -> new BoundedNames(MAX_STARSECTOR_API_CLASSES_PER_MOD));
                    names.add(reference);
                    truncated |= names.truncated();
                }

                List<Provider> providers = providerIndex.providersByClass().get(reference);
                if (providers == null) {
                    continue;
                }
                LinkedHashSet<String> providerMods = new LinkedHashSet<>();
                for (Provider provider : providers) {
                    providerMods.add(provider.modId());
                }
                boolean selfProvides = providerMods.contains(jar.modId());
                LinkedHashSet<String> otherMods = new LinkedHashSet<>(providerMods);
                otherMods.remove(jar.modId());

                if (!providerInventoryComplete) {
                    if (!otherMods.isEmpty()) {
                        String incompleteKey = jar.modId() + "\u0000" + reference;
                        if (!seenIncomplete.contains(incompleteKey)) {
                            if (seenIncomplete.size() >= MAX_AMBIGUOUS_REFERENCES) {
                                truncated = true;
                                continue;
                            }
                            seenIncomplete.add(incompleteKey);
                            incompleteProviderCount++;
                            if (incompleteProviderSamples.size() < MAX_INCOMPLETE_PROVIDER_SAMPLES) {
                                Map<String, Object> sample = new LinkedHashMap<>();
                                sample.put("fromMod", jar.modId());
                                sample.put("className", reference);
                                sample.put("knownProviderMods", List.copyOf(providerMods));
                                sample.put("providerInventoryComplete", false);
                                incompleteProviderSamples.add(sample);
                            }
                        }
                    }
                    continue;
                }

                if (!selfProvides && otherMods.size() == 1) {
                    String target = otherMods.iterator().next();
                    EdgeKey key = new EdgeKey(jar.modId(), target);
                    BoundedNames names = edges.get(key);
                    if (names == null) {
                        if (edges.size() >= MAX_STATIC_EDGES) {
                            truncated = true;
                            continue;
                        }
                        names = new BoundedNames(MAX_EDGE_CLASSES);
                        edges.put(key, names);
                    }
                    names.add(reference);
                    truncated |= names.truncated();
                } else if (!otherMods.isEmpty() && (selfProvides || otherMods.size() > 1)) {
                    String ambiguityKey = jar.modId() + "\u0000" + reference;
                    if (!seenAmbiguous.contains(ambiguityKey)) {
                        if (seenAmbiguous.size() >= MAX_AMBIGUOUS_REFERENCES) {
                            truncated = true;
                            continue;
                        }
                        seenAmbiguous.add(ambiguityKey);
                        ambiguousCount++;
                        if (ambiguousSamples.size() < MAX_AMBIGUOUS_SAMPLES) {
                            Map<String, Object> sample = new LinkedHashMap<>();
                            sample.put("fromMod", jar.modId());
                            sample.put("className", reference);
                            sample.put("providerMods", List.copyOf(providerMods));
                            sample.put("providerInventoryComplete", true);
                            ambiguousSamples.add(sample);
                        }
                    }
                }
            }
        }

        List<Edge> edgeResults = edges.entrySet().stream()
                .map(entry -> new Edge(
                        entry.getKey().fromMod(),
                        entry.getKey().toMod(),
                        inputs.dependenciesByMod().getOrDefault(entry.getKey().fromMod(), Set.of())
                                .contains(entry.getKey().toMod()),
                        entry.getValue().size(),
                        entry.getValue().sample(),
                        entry.getValue().truncated()))
                .toList();
        List<ApiUsage> apiResults = starsectorApi.entrySet().stream()
                .map(entry -> {
                    boolean complete = staticScanComplete.getOrDefault(entry.getKey(), false);
                    return new ApiUsage(
                            entry.getKey(),
                            entry.getValue().size(),
                            entry.getValue().sample(),
                            entry.getValue().truncated() || !complete,
                            complete);
                })
                .toList();
        return new RelationshipSummary(
                List.copyOf(edgeResults),
                List.copyOf(apiResults),
                ambiguousCount,
                List.copyOf(ambiguousSamples),
                incompleteProviderCount,
                List.copyOf(incompleteProviderSamples),
                truncated);
    }

    private static void sample(List<String> errors, String value) {
        if (errors.size() < MAX_ERROR_SAMPLES_PER_JAR) {
            errors.add(value);
        }
    }

    record Result(Map<String, Object> values) {
        Result {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        String toJson() {
            return Json.object(values);
        }
    }

    private record Inputs(
            Map<String, Set<String>> dependenciesByMod,
            List<JarInput> jars,
            Set<String> unresolvedMods,
            Set<String> invalidJarMods,
            boolean jarInventoryComplete,
            int auditedJarCount,
            int invalidAuditedJarCount) {
        static Inputs from(Map<String, Object> values) {
            Map<String, Set<String>> dependencies = new LinkedHashMap<>();
            Map<String, Path> directories = new LinkedHashMap<>();
            Set<String> unresolved = new LinkedHashSet<>();
            Object rawMods = values.get("mods");
            if (rawMods instanceof List<?> modValues) {
                for (Object raw : modValues) {
                    if (!(raw instanceof Map<?, ?> mod)) {
                        continue;
                    }
                    Object idValue = mod.get("id");
                    if (!(idValue instanceof String id)) {
                        continue;
                    }
                    Object directory = mod.get("directory");
                    if (directory instanceof Path path) {
                        directories.put(id, path);
                    } else {
                        unresolved.add(id);
                    }
                    LinkedHashSet<String> declared = new LinkedHashSet<>();
                    Object rawDependencies = mod.get("dependencies");
                    if (rawDependencies instanceof List<?> list) {
                        for (Object dependency : list) {
                            if (dependency instanceof String value) {
                                declared.add(value);
                            }
                        }
                    }
                    dependencies.put(id, Collections.unmodifiableSet(declared));
                }
            }

            List<JarInput> jars = new ArrayList<>();
            Set<String> invalidJarMods = new LinkedHashSet<>();
            boolean inventoryComplete = true;
            int audited = 0;
            int invalid = 0;
            Object rawJars = values.get("jars");
            if (rawJars instanceof List<?> jarValues) {
                for (Object raw : jarValues) {
                    audited++;
                    if (!(raw instanceof Map<?, ?> jar)) {
                        inventoryComplete = false;
                        invalid++;
                        continue;
                    }
                    Object modValue = jar.get("modId");
                    Object relativeValue = jar.get("relativePath");
                    if (!(modValue instanceof String modId) || !(relativeValue instanceof String relativePath)) {
                        inventoryComplete = false;
                        invalid++;
                        continue;
                    }
                    if (!Boolean.TRUE.equals(jar.get("valid"))) {
                        inventoryComplete = false;
                        invalid++;
                        invalidJarMods.add(modId);
                        continue;
                    }
                    Path directory = directories.get(modId);
                    if (directory == null) {
                        inventoryComplete = false;
                        invalid++;
                        invalidJarMods.add(modId);
                        continue;
                    }
                    jars.add(new JarInput(modId, directory, relativePath));
                }
            }
            return new Inputs(
                    Collections.unmodifiableMap(dependencies),
                    List.copyOf(jars),
                    Collections.unmodifiableSet(unresolved),
                    Collections.unmodifiableSet(invalidJarMods),
                    inventoryComplete,
                    audited,
                    invalid);
        }
    }

    private record JarInput(String modId, Path directory, String relativePath) {
    }

    private record JarEvidence(
            String modId,
            String relativePath,
            long scannedClassFiles,
            long unreadableClassFiles,
            long classBytesRead,
            boolean truncated,
            Set<String> verifiedClasses,
            Set<String> references,
            List<String> errors) {
        static JarEvidence failure(JarInput jar, String error) {
            return new JarEvidence(
                    jar.modId(), jar.relativePath(), 0, 0, 0, true, Set.of(), Set.of(), List.of(error));
        }

        boolean complete() {
            return !truncated && unreadableClassFiles == 0;
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("modId", modId);
            values.put("relativePath", relativePath);
            values.put("scannedClassFiles", scannedClassFiles);
            values.put("unreadableClassFiles", unreadableClassFiles);
            values.put("classBytesRead", classBytesRead);
            values.put("uniqueClassReferences", references.size());
            values.put("complete", complete());
            values.put("truncated", truncated);
            values.put("errors", errors);
            return values;
        }
    }

    private record Provider(String modId, String relativePath) {
    }

    private record ProviderIndex(Map<String, List<Provider>> providersByClass, long verifiedClassCount, boolean truncated) {
        static ProviderIndex from(List<JarEvidence> evidence, int maximumVerifiedClasses) {
            Map<String, List<Provider>> providers = new TreeMap<>();
            long count = 0;
            boolean truncated = false;
            outer:
            for (JarEvidence jar : evidence) {
                for (String className : jar.verifiedClasses()) {
                    if (count >= maximumVerifiedClasses) {
                        truncated = true;
                        break outer;
                    }
                    providers.computeIfAbsent(className, ignored -> new ArrayList<>())
                            .add(new Provider(jar.modId(), jar.relativePath()));
                    count++;
                }
            }
            Map<String, List<Provider>> immutable = new TreeMap<>();
            providers.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
            return new ProviderIndex(Collections.unmodifiableMap(immutable), count, truncated);
        }
    }

    private record EdgeKey(String fromMod, String toMod) implements Comparable<EdgeKey> {
        @Override
        public int compareTo(EdgeKey other) {
            int from = fromMod.compareTo(other.fromMod);
            return from != 0 ? from : toMod.compareTo(other.toMod);
        }
    }

    private record Edge(
            String fromMod,
            String toMod,
            boolean declaredDependency,
            long referencedClasses,
            List<String> sampleClasses,
            boolean truncated) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("fromMod", fromMod);
            values.put("toMod", toMod);
            values.put("declaredDependency", declaredDependency);
            values.put("referencedClasses", referencedClasses);
            values.put("sampleClasses", sampleClasses);
            values.put("truncated", truncated);
            values.put("providerInventoryComplete", true);
            return values;
        }
    }

    private record ApiUsage(
            String modId,
            long referencedClasses,
            List<String> sampleClasses,
            boolean truncated,
            boolean staticScanComplete) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("modId", modId);
            values.put("referencedClasses", referencedClasses);
            values.put("sampleClasses", sampleClasses);
            values.put("truncated", truncated);
            values.put("staticScanComplete", staticScanComplete);
            return values;
        }
    }

    private record RelationshipSummary(
            List<Edge> edges,
            List<ApiUsage> starsectorApi,
            long ambiguousCount,
            List<Map<String, Object>> ambiguousSamples,
            long incompleteProviderCount,
            List<Map<String, Object>> incompleteProviderSamples,
            boolean truncated) {
    }

    private static final class BoundedNames {
        private final int limit;
        private final LinkedHashSet<String> names = new LinkedHashSet<>();
        private boolean truncated;

        BoundedNames(int limit) {
            this.limit = limit;
        }

        void add(String name) {
            if (names.contains(name)) {
                return;
            }
            if (names.size() >= limit) {
                truncated = true;
                return;
            }
            names.add(name);
        }

        long size() {
            return names.size();
        }

        List<String> sample() {
            return names.stream().limit(32).toList();
        }

        boolean truncated() {
            return truncated;
        }
    }
}
