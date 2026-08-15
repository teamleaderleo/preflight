package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Exact bounded signatures for the source compiler and loader classes needed by the bytecode cache. */
final class CodeLoaderSignatureReport {
    static final int IDENTITY_LIMIT = 128;
    private static final int METHOD_LIMIT = 1_024;
    private static final int DIAGNOSTIC_LIMIT = 100;
    private static final Set<String> TARGETS = Set.of(
            "org/codehaus/janino/JavaSourceClassLoader",
            "org/codehaus/janino/JavaSourceIClassLoader",
            "org/codehaus/janino/UnitCompiler",
            "org/codehaus/janino/Parser",
            "org/codehaus/janino/ClassLoaderIClassLoader",
            "org/codehaus/commons/compiler/AbstractJavaSourceClassLoader",
            "org/codehaus/commons/compiler/util/resource/ResourceFinder",
            "org/codehaus/commons/compiler/util/resource/PathResourceFinder",
            "org/codehaus/commons/compiler/util/resource/DirectoryResourceFinder");

    private final Path destination;
    private final Instant startedAt = Instant.now();
    private final TreeMap<IdentityKey, Entry> entries = new TreeMap<>();
    private final List<String> diagnostics = new ArrayList<>();
    private boolean entriesTruncated;
    private boolean diagnosticsTruncated;

    CodeLoaderSignatureReport(Path destination) {
        this.destination = destination.toAbsolutePath().normalize();
    }

    boolean interested(String internalClassName) {
        return internalClassName != null && TARGETS.contains(internalClassName.replace('.', '/'));
    }

    synchronized void observed(ClassSignature signature, AdapterSourceIdentity source) {
        if (!interested(signature.internalName())) return;
        IdentityKey key = new IdentityKey(
                signature.internalName(),
                signature.sha256(),
                source.normalizedSource(),
                source.loaderClass(),
                source.loaderName());
        if (entries.containsKey(key)) return;

        List<Method> methods = signature.methods().stream()
                .sorted(Comparator.comparing(ClassSignature.Method::name)
                        .thenComparing(ClassSignature.Method::descriptor)
                        .thenComparingInt(ClassSignature.Method::access))
                .limit(METHOD_LIMIT)
                .map(method -> new Method(method.name(), method.descriptor(), method.access()))
                .toList();
        Entry entry = new Entry(
                signature.internalName(),
                signature.sha256(),
                signature.majorVersion(),
                signature.methods().size(),
                methods,
                signature.methods().size() > METHOD_LIMIT,
                source.codeSource(),
                source.normalizedSource(),
                source.sourceKind(),
                source.sourceSha256(),
                source.sourceHashProblem(),
                source.loaderClass(),
                source.loaderName());
        retainIdentity(key, entry);
    }

    synchronized void contained(String detail, Throwable error) {
        diagnostic(detail + ": " + message(error));
    }

    /**
     * Whether this run observed nothing a reader would act on.
     *
     * <p>The interesting content of a signature report is the part that says something went other
     * than expected: a containment diagnostic, or a limit hit so hard the record is incomplete. The
     * rest is the same list of Janino classes every launch of the same install produces, and
     * writing it again per launch fills a player's disk with copies nothing reads.
     */
    synchronized boolean routine() {
        return diagnostics.isEmpty() && !diagnosticsTruncated && !entriesTruncated;
    }

    synchronized void write() throws IOException {
        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.now());
        root.put("startedAt", startedAt);
        root.put("destination", destination);
        root.put("format", "starsector-preflight-code-loader-signatures-v1");
        root.put("automaticTargetGenerated", false);
        root.put("liveTransformationEligible", false);
        root.put("requiresHumanReview", true);
        root.put("targetClassNames", TARGETS.stream().sorted().toList());
        root.put("identityLimit", IDENTITY_LIMIT);
        root.put("retainedIdentities", entries.size());
        root.put("entriesTruncated", entriesTruncated);
        root.put("entries", entries.values().stream().map(Entry::toMap).toList());
        root.put("diagnosticsTruncated", diagnosticsTruncated);
        root.put("diagnostics", List.copyOf(diagnostics));
        writeAtomic(destination, Json.object(root) + System.lineSeparator());
    }

    private void retainIdentity(IdentityKey key, Entry entry) {
        if (entries.size() < IDENTITY_LIMIT) {
            entries.put(key, entry);
            return;
        }
        entriesTruncated = true;
        IdentityKey largest = entries.lastKey();
        if (key.compareTo(largest) < 0) {
            entries.pollLastEntry();
            entries.put(key, entry);
        }
    }

    private void diagnostic(String detail) {
        if (diagnostics.size() >= DIAGNOSTIC_LIMIT) {
            diagnosticsTruncated = true;
        } else {
            diagnostics.add(detail == null ? "" : detail);
        }
    }

    private static void writeAtomic(Path destination, String content) throws IOException {
        Path temporary = destination.resolveSibling(
                destination.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            Files.writeString(
                    temporary,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    private record IdentityKey(
            String className,
            String classSha256,
            String normalizedSource,
            String loaderClass,
            String loaderName) implements Comparable<IdentityKey> {
        @Override
        public int compareTo(IdentityKey other) {
            int order = className.compareTo(other.className);
            if (order != 0) return order;
            order = classSha256.compareTo(other.classSha256);
            if (order != 0) return order;
            order = normalizedSource.compareTo(other.normalizedSource);
            if (order != 0) return order;
            order = loaderClass.compareTo(other.loaderClass);
            if (order != 0) return order;
            return loaderName.compareTo(other.loaderName);
        }
    }

    private record Entry(
            String className,
            String classSha256,
            int majorVersion,
            int methodCount,
            List<Method> methods,
            boolean methodsTruncated,
            String codeSource,
            String normalizedSource,
            String sourceKind,
            String sourceSha256,
            String sourceHashProblem,
            String loaderClass,
            String loaderName) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("className", className);
            values.put("classSha256", classSha256);
            values.put("majorVersion", majorVersion);
            values.put("methodCount", methodCount);
            values.put("methods", methods.stream().map(Method::toMap).toList());
            values.put("methodsTruncated", methodsTruncated);
            values.put("codeSource", codeSource);
            values.put("normalizedSource", normalizedSource);
            values.put("sourceKind", sourceKind);
            values.put("sourceSha256", sourceSha256.isBlank() ? null : sourceSha256);
            values.put("sourceHashProblem", sourceHashProblem.isBlank() ? null : sourceHashProblem);
            values.put("loaderClass", loaderClass);
            values.put("loaderName", loaderName);
            return values;
        }
    }

    private record Method(String name, String descriptor, int access) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("name", name);
            values.put("descriptor", descriptor);
            values.put("access", access);
            return values;
        }
    }
}
