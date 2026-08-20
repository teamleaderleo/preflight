package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
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
    private static final int CLASSFILE_MAGIC = 0xcafebabe;
    private static final int MAX_CONSTANT_POOL_ENTRIES = 65_535;
    private static final int MAX_JARS = 512;
    private static final int MAX_CLASSFILES_PER_JAR = 50_000;
    private static final int MAX_CLASSFILE_BYTES = 4 * 1024 * 1024;
    private static final long MAX_CLASS_BYTES_PER_JAR = 128L * 1024 * 1024;
    private static final long MAX_CLASS_BYTES_TOTAL = 512L * 1024 * 1024;
    private static final int MAX_REFERENCES_PER_JAR = 100_000;
    private static final int MAX_VERIFIED_CLASSES = 250_000;
    private static final int MAX_EDGE_CLASSES = 4_096;
    private static final int MAX_STARSECTOR_API_CLASSES_PER_MOD = 4_096;
    private static final int MAX_ERROR_SAMPLES_PER_JAR = 8;
    private static final int MAX_AMBIGUOUS_SAMPLES = 128;
    private static final String STARSECTOR_API_PREFIX = "com.fs.starfarer.api.";

    private ModJarApiUsage() {
    }

    static Result scan(Path installRoot) throws IOException {
        ClasspathAudit.Result classpath = ClasspathAudit.scan(installRoot);
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

        ProviderIndex providers = ProviderIndex.from(evidence);
        RelationshipSummary relationships = relationships(inputs, evidence, providers);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("enabledMods", inputs.dependenciesByMod().size());
        totals.put("candidateJars", inputs.jars().size());
        totals.put("scannedJars", evidence.size());
        totals.put("scannedClassFiles", evidence.stream().mapToLong(JarEvidence::scannedClassFiles).sum());
        totals.put("unreadableClassFiles", evidence.stream().mapToLong(JarEvidence::unreadableClassFiles).sum());
        totals.put("verifiedClassNames", providers.verifiedClassCount());
        totals.put("classBytesRead", evidence.stream().mapToLong(JarEvidence::classBytesRead).sum());
        totals.put("staticModEdges", relationships.edges().size());
        totals.put("undeclaredStaticModEdges", relationships.edges().stream()
                .filter(edge -> !edge.declaredDependency())
                .count());
        totals.put("modsReferencingStarsectorApi", relationships.starsectorApi().size());
        totals.put("ambiguousStaticReferences", relationships.ambiguousCount());
        totals.put("truncated", jarsTruncated
                || providers.truncated()
                || evidence.stream().anyMatch(JarEvidence::truncated)
                || relationships.truncated());

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("evidenceKind", "constant-pool-class-reference-v1");
        values.put("archiveFingerprint", classpath.values().get("archiveFingerprint"));
        values.put("classpathFingerprint", classpath.values().get("classpathFingerprint"));
        values.put("totals", totals);
        values.put("staticModReferences", relationships.edges().stream().map(Edge::toMap).toList());
        values.put("starsectorApiUsage", relationships.starsectorApi().stream().map(ApiUsage::toMap).toList());
        values.put("ambiguousStaticReferenceSamples", relationships.ambiguousSamples());
        values.put("jarScans", evidence.stream().map(JarEvidence::toMap).toList());
        values.put("notes", List.of(
                "Static bytecode evidence only; no mod classes were loaded or executed.",
                "A static reference does not by itself prove that the code path runs in a given session.",
                "An undeclared cross-mod reference may be an intentional optional integration, not a defect."));
        return new Result(values);
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
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .limit(MAX_CLASSFILES_PER_JAR + 1L)
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
                }
                bytesRead += classfile.length;
                if (classfile.length > payloadLimit) {
                    unreadableClasses++;
                    truncated = true;
                    sample(errors, entry.getName() + ": classfile exceeded remaining read budget");
                    break;
                }

                try {
                    ClassEvidence parsed = parseClass(classfile);
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
                    Set.of(), Set.of(), List.of(error.getMessage()));
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

    private static ClassEvidence parseClass(byte[] classfile) throws IOException {
        if (classfile.length < 10) {
            throw new IOException("classfile is too small");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(classfile))) {
            if (input.readInt() != CLASSFILE_MAGIC) {
                throw new IOException("invalid classfile magic");
            }
            input.readUnsignedShort();
            input.readUnsignedShort();
            int count = input.readUnsignedShort();
            if (count < 2 || count > MAX_CONSTANT_POOL_ENTRIES) {
                throw new IOException("invalid constant-pool count: " + count);
            }

            byte[] tags = new byte[count];
            int[] classNameIndexes = new int[count];
            String[] utf8 = new String[count];
            for (int index = 1; index < count; index++) {
                int tag = input.readUnsignedByte();
                tags[index] = (byte) tag;
                switch (tag) {
                    case 1 -> utf8[index] = input.readUTF();
                    case 3, 4 -> skipFully(input, 4);
                    case 5, 6 -> {
                        skipFully(input, 8);
                        index++;
                    }
                    case 7 -> classNameIndexes[index] = input.readUnsignedShort();
                    case 8, 16, 19, 20 -> skipFully(input, 2);
                    case 9, 10, 11, 12, 17, 18 -> skipFully(input, 4);
                    case 15 -> skipFully(input, 3);
                    default -> throw new IOException("unsupported constant-pool tag: " + tag);
                }
            }

            input.readUnsignedShort();
            int thisClass = input.readUnsignedShort();
            String binaryName = className(tags, classNameIndexes, utf8, thisClass);
            if (binaryName == null || binaryName.startsWith("[") || binaryName.indexOf('.') < 0) {
                throw new IOException("invalid this_class identity");
            }

            LinkedHashSet<String> references = new LinkedHashSet<>();
            for (int index = 1; index < count; index++) {
                if (tags[index] != 7) {
                    continue;
                }
                String name = className(tags, classNameIndexes, utf8, index);
                if (name == null) {
                    throw new IOException("invalid CONSTANT_Class entry at " + index);
                }
                addClassConstant(references, name);
            }
            references.remove(binaryName);
            return new ClassEvidence(binaryName, Collections.unmodifiableSet(references));
        } catch (EOFException error) {
            throw new IOException("classfile ended inside its constant pool", error);
        }
    }

    private static String className(byte[] tags, int[] classNameIndexes, String[] utf8, int classIndex) {
        if (classIndex <= 0 || classIndex >= tags.length || tags[classIndex] != 7) {
            return null;
        }
        int nameIndex = classNameIndexes[classIndex];
        if (nameIndex <= 0 || nameIndex >= tags.length || tags[nameIndex] != 1 || utf8[nameIndex] == null) {
            return null;
        }
        String internal = utf8[nameIndex];
        if (internal.isBlank()) {
            return null;
        }
        if (internal.startsWith("[")) {
            return internal;
        }
        if (internal.indexOf('.') >= 0) {
            return null;
        }
        return internal.replace('/', '.');
    }

    private static void addClassConstant(Set<String> output, String name) {
        if (!name.startsWith("[")) {
            if (name.indexOf('.') >= 0) {
                output.add(name);
            }
            return;
        }
        int cursor = 0;
        while (cursor < name.length()) {
            int marker = name.indexOf('L', cursor);
            if (marker < 0) {
                return;
            }
            int end = name.indexOf(';', marker + 1);
            if (end < 0) {
                return;
            }
            String internal = name.substring(marker + 1, end);
            if (!internal.isBlank() && internal.indexOf('.') < 0) {
                output.add(internal.replace('/', '.'));
            }
            cursor = end + 1;
        }
    }

    private static void skipFully(DataInputStream input, int bytes) throws IOException {
        if (input.skipBytes(bytes) != bytes) {
            throw new EOFException("classfile ended inside its constant pool");
        }
    }

    private static RelationshipSummary relationships(
            Inputs inputs, List<JarEvidence> evidence, ProviderIndex providerIndex) {
        Map<EdgeKey, BoundedNames> edges = new TreeMap<>();
        Map<String, BoundedNames> starsectorApi = new TreeMap<>();
        List<Map<String, Object>> ambiguousSamples = new ArrayList<>();
        Set<String> seenAmbiguous = new LinkedHashSet<>();
        long ambiguousCount = 0;
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
                LinkedHashSet<String> otherMods = new LinkedHashSet<>();
                for (Provider provider : providers) {
                    if (!provider.modId().equals(jar.modId())) {
                        otherMods.add(provider.modId());
                    }
                }
                if (otherMods.size() == 1) {
                    String target = otherMods.iterator().next();
                    BoundedNames names = edges.computeIfAbsent(
                            new EdgeKey(jar.modId(), target), ignored -> new BoundedNames(MAX_EDGE_CLASSES));
                    names.add(reference);
                    truncated |= names.truncated();
                } else if (otherMods.size() > 1) {
                    String ambiguityKey = jar.modId() + "\u0000" + reference;
                    if (seenAmbiguous.add(ambiguityKey)) {
                        ambiguousCount++;
                        if (ambiguousSamples.size() < MAX_AMBIGUOUS_SAMPLES) {
                            Map<String, Object> sample = new LinkedHashMap<>();
                            sample.put("fromMod", jar.modId());
                            sample.put("className", reference);
                            sample.put("providerMods", List.copyOf(otherMods));
                            ambiguousSamples.add(sample);
                        } else {
                            truncated = true;
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
                .map(entry -> new ApiUsage(
                        entry.getKey(), entry.getValue().size(), entry.getValue().sample(), entry.getValue().truncated()))
                .toList();
        return new RelationshipSummary(
                List.copyOf(edgeResults), List.copyOf(apiResults), ambiguousCount,
                List.copyOf(ambiguousSamples), truncated);
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

    private record Inputs(Map<String, Set<String>> dependenciesByMod, List<JarInput> jars) {
        @SuppressWarnings("unchecked")
        static Inputs from(Map<String, Object> values) {
            Map<String, Set<String>> dependencies = new LinkedHashMap<>();
            Map<String, Path> directories = new LinkedHashMap<>();
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
            Object rawJars = values.get("jars");
            if (rawJars instanceof List<?> jarValues) {
                for (Object raw : jarValues) {
                    if (!(raw instanceof Map<?, ?> jar) || !Boolean.TRUE.equals(jar.get("valid"))) {
                        continue;
                    }
                    Object modValue = jar.get("modId");
                    Object relativeValue = jar.get("relativePath");
                    if (!(modValue instanceof String modId) || !(relativeValue instanceof String relativePath)) {
                        continue;
                    }
                    Path directory = directories.get(modId);
                    if (directory != null) {
                        jars.add(new JarInput(modId, directory, relativePath));
                    }
                }
            }
            return new Inputs(Collections.unmodifiableMap(dependencies), List.copyOf(jars));
        }
    }

    private record JarInput(String modId, Path directory, String relativePath) {
    }

    private record ClassEvidence(String binaryName, Set<String> references) {
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

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("modId", modId);
            values.put("relativePath", relativePath);
            values.put("scannedClassFiles", scannedClassFiles);
            values.put("unreadableClassFiles", unreadableClassFiles);
            values.put("classBytesRead", classBytesRead);
            values.put("uniqueClassReferences", references.size());
            values.put("truncated", truncated);
            values.put("errors", errors);
            return values;
        }
    }

    private record Provider(String modId, String relativePath) {
    }

    private record ProviderIndex(Map<String, List<Provider>> providersByClass, long verifiedClassCount, boolean truncated) {
        static ProviderIndex from(List<JarEvidence> evidence) {
            Map<String, List<Provider>> providers = new TreeMap<>();
            long count = 0;
            boolean truncated = false;
            outer:
            for (JarEvidence jar : evidence) {
                for (String className : jar.verifiedClasses()) {
                    if (count >= MAX_VERIFIED_CLASSES) {
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
            return values;
        }
    }

    private record ApiUsage(String modId, long referencedClasses, List<String> sampleClasses, boolean truncated) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("modId", modId);
            values.put("referencedClasses", referencedClasses);
            values.put("sampleClasses", sampleClasses);
            values.put("truncated", truncated);
            return values;
        }
    }

    private record RelationshipSummary(
            List<Edge> edges,
            List<ApiUsage> starsectorApi,
            long ambiguousCount,
            List<Map<String, Object>> ambiguousSamples,
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
