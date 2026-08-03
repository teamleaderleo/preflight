package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedBytecodeContextEvidenceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void completeEvidenceIsDeterministicAndOrderSensitive() {
        GeneratedBytecodeContextEvidence first = evidence(
                classpath("1", "2"),
                lookups("3", "4"),
                settings("source", "UTF-8"),
                GeneratedBytecodeContextEvidence.Completion.complete());
        GeneratedBytecodeContextEvidence copy = evidence(
                classpath("1", "2"),
                lookups("3", "4"),
                settings("source", "UTF-8"),
                GeneratedBytecodeContextEvidence.Completion.complete());

        GeneratedBytecodeContext firstContext = first.exactContextFor(Root.class.getName()).orElseThrow();
        GeneratedBytecodeContext copyContext = copy.exactContextFor(Root.class.getName()).orElseThrow();
        assertEquals(first.evidenceSha256(), copy.evidenceSha256());
        assertEquals(firstContext.keySha256(), copyContext.keySha256());

        GeneratedBytecodeContextEvidence classpathReordered = evidence(
                classpath("2", "1"),
                lookups("3", "4"),
                settings("source", "UTF-8"),
                GeneratedBytecodeContextEvidence.Completion.complete());
        GeneratedBytecodeContextEvidence lookupsReordered = evidence(
                classpath("1", "2"),
                lookups("4", "3"),
                settings("source", "UTF-8"),
                GeneratedBytecodeContextEvidence.Completion.complete());
        GeneratedBytecodeContextEvidence optionsChanged = evidence(
                classpath("1", "2"),
                lookups("3", "4"),
                settings("target", "17"),
                GeneratedBytecodeContextEvidence.Completion.complete());

        assertNotEquals(
                firstContext.keySha256(),
                classpathReordered.exactContextFor(Root.class.getName()).orElseThrow().keySha256());
        assertNotEquals(
                firstContext.keySha256(),
                lookupsReordered.exactContextFor(Root.class.getName()).orElseThrow().keySha256());
        assertNotEquals(
                firstContext.keySha256(),
                optionsChanged.exactContextFor(Root.class.getName()).orElseThrow().keySha256());
    }

    @Test
    void portableTokenRoundTripsAllExactComponents() {
        GeneratedBytecodeContext context = new GeneratedBytecodeContext(
                hash("0"), hash("1"), hash("2"), hash("3"), hash("4"), hash("5"), hash("6"));
        assertEquals(context, GeneratedBytecodeContext.fromPortableToken(context.portableToken()));
        assertThrows(IllegalArgumentException.class,
                () -> GeneratedBytecodeContext.fromPortableToken(hash("0")));
        assertThrows(IllegalArgumentException.class,
                () -> GeneratedBytecodeContext.fromPortableToken(
                        String.join(".", hash("0"), hash("1"), hash("2"), hash("3"),
                                hash("4"), hash("5"), "not-a-hash")));
    }

    @Test
    void summaryRedactsNamesSettingsAndContents() {
        String sourceName = "mods/private/source/SecretCompilerUnit.java";
        String optionValue = "/private/mod/path/with-sensitive-value";
        String content = "class ProprietarySourceBody {}";
        GeneratedBytecodeContextEvidence evidence = new GeneratedBytecodeContextEvidence(
                Root.class.getName(),
                hash("0"),
                hash("1"),
                classpath("2", "3"),
                List.of(GeneratedBytecodeContextEvidence.Lookup.observed(
                        GeneratedBytecodeContextEvidence.LookupKind.SOURCE,
                        sourceName,
                        hash("2"),
                        GeneratedBytecodeContextEvidence.LookupOutcome.FOUND,
                        Hashes.sha256(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)))),
                List.of(GeneratedBytecodeContextEvidence.CompilerSetting.observed("sourcePath", optionValue)),
                "UTF-8",
                hash("4"),
                hash("5"),
                GeneratedBytecodeContextEvidence.Completion.complete());

        String summary = evidence.summaryFor(Root.class.getName()).toString();
        assertFalse(summary.contains(sourceName), summary);
        assertFalse(summary.contains(optionValue), summary);
        assertFalse(summary.contains(content), summary);
        assertTrue(summary.contains("sourceContentsIncluded=false"), summary);
        assertTrue(summary.contains("observedNamesIncluded=false"), summary);
        assertTrue(summary.contains("compilerSettingValuesIncluded=false"), summary);
    }

    @Test
    void incompleteOrMismatchedEvidenceCannotProduceContext() {
        GeneratedBytecodeContextEvidence.Completion incomplete = new GeneratedBytecodeContextEvidence.Completion(
                true, false, true, true, true, true, false, true);
        GeneratedBytecodeContextEvidence evidence = evidence(
                classpath("1"), lookups("2"), settings("source", "17"), incomplete);

        assertTrue(evidence.exactContextFor(Root.class.getName()).isEmpty());
        assertEquals(
                List.of("source-graph-open", "duplicate-behavior-unproven"),
                evidence.incompleteReasonsFor(Root.class.getName()));
        assertTrue(evidence.exactContextFor("different.Root").isEmpty());
        assertEquals(
                "requested-class-mismatch",
                evidence.incompleteReasonsFor("different.Root").get(0));
    }

    @Test
    void boundsAndLookupInvariantsAreEnforced() {
        List<GeneratedBytecodeContextEvidence.ClasspathEntry> oversized = new ArrayList<>();
        for (int index = 0; index <= GeneratedBytecodeContextEvidence.MAX_CLASSPATH_ENTRIES; index++) {
            oversized.add(new GeneratedBytecodeContextEvidence.ClasspathEntry(
                    GeneratedBytecodeContextEvidence.ProviderKind.ARCHIVE,
                    hash(Integer.toHexString(index % 16))));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> evidence(
                        oversized,
                        lookups("2"),
                        settings("source", "17"),
                        GeneratedBytecodeContextEvidence.Completion.complete()));

        assertThrows(
                IllegalArgumentException.class,
                () -> GeneratedBytecodeContextEvidence.Lookup.observed(
                        GeneratedBytecodeContextEvidence.LookupKind.RESOURCE,
                        "resource",
                        hash("3"),
                        GeneratedBytecodeContextEvidence.LookupOutcome.MISS,
                        hash("4")));
        assertThrows(
                IllegalArgumentException.class,
                () -> GeneratedBytecodeContextEvidence.Token.observed(
                        "x".repeat(GeneratedBytecodeContextEvidence.MAX_OBSERVED_TEXT_CHARS + 1),
                        "oversized"));
    }

    @Test
    void incompleteEvidenceBypassesExistingHitAndNeverWrites() throws Exception {
        Path cache = temporaryDirectory.resolve("cache");
        GeneratedBytecodeContextEvidence complete = evidence(
                classpath("1", "2"),
                lookups("3", "4"),
                settings("source", "17"),
                GeneratedBytecodeContextEvidence.Completion.complete());
        LinkedHashMap<String, byte[]> cached = completeMap();
        GeneratedBytecodeCacheWrapper.Result stored = GeneratedBytecodeCacheWrapper.generate(
                cache, complete, Root.class.getName(), requested -> cached);
        assertEquals(GeneratedBytecodeCacheWrapper.Source.ORIGINAL_STORED, stored.source());

        GeneratedBytecodeContextEvidence.Completion incompleteFlags = new GeneratedBytecodeContextEvidence.Completion(
                true, false, true, true, true, true, true, true);
        GeneratedBytecodeContextEvidence incomplete = evidence(
                classpath("1", "2"),
                lookups("3", "4"),
                settings("source", "17"),
                incompleteFlags);
        LinkedHashMap<String, byte[]> original = completeMap();
        AtomicInteger calls = new AtomicInteger();
        GeneratedBytecodeCacheWrapper.Result result = GeneratedBytecodeCacheWrapper.generate(
                cache,
                incomplete,
                Root.class.getName(),
                requested -> {
                    calls.incrementAndGet();
                    return original;
                });

        assertEquals(1, calls.get());
        assertEquals(GeneratedBytecodeCacheWrapper.Source.ORIGINAL_CONTEXT_INCOMPLETE, result.source());
        assertEquals(GeneratedBytecodeCache.Status.MISS, result.lookupStatus());
        assertSame(original, result.classes());
        assertFalse(result.cacheUsable());
        assertEquals("source-graph-open", result.detail());

        Path emptyCache = temporaryDirectory.resolve("empty-cache");
        GeneratedBytecodeCacheWrapper.generate(
                emptyCache, incomplete, Root.class.getName(), requested -> completeMap());
        assertFalse(Files.exists(emptyCache), "incomplete context unexpectedly created a cache root");
    }

    @Test
    void completeEvidenceDelegatesToExactHitAndIncompleteBypassPreservesExceptionIdentity() throws Exception {
        Path cache = temporaryDirectory.resolve("cache");
        GeneratedBytecodeContextEvidence complete = evidence(
                classpath("1"),
                lookups("2"),
                settings("source", "17"),
                GeneratedBytecodeContextEvidence.Completion.complete());
        GeneratedBytecodeCacheWrapper.generate(cache, complete, Root.class.getName(), requested -> completeMap());

        AtomicInteger calls = new AtomicInteger();
        GeneratedBytecodeCacheWrapper.Result hit = GeneratedBytecodeCacheWrapper.generate(
                cache,
                complete,
                Root.class.getName(),
                requested -> {
                    calls.incrementAndGet();
                    throw new AssertionError("original generator ran on an exact evidence hit");
                });
        assertEquals(GeneratedBytecodeCacheWrapper.Source.CACHE_HIT, hit.source());
        assertEquals(0, calls.get());

        GeneratedBytecodeContextEvidence.Completion incompleteFlags = new GeneratedBytecodeContextEvidence.Completion(
                false, true, true, true, true, true, true, true);
        GeneratedBytecodeContextEvidence incomplete = evidence(
                classpath("1"), lookups("2"), settings("source", "17"), incompleteFlags);
        SyntheticCompilationException expected = new SyntheticCompilationException("exact exception object");
        SyntheticCompilationException actual = assertThrows(
                SyntheticCompilationException.class,
                () -> GeneratedBytecodeCacheWrapper.generate(
                        cache,
                        incomplete,
                        Root.class.getName(),
                        requested -> {
                            throw expected;
                        }));
        assertSame(expected, actual);
    }

    private static GeneratedBytecodeContextEvidence evidence(
            List<GeneratedBytecodeContextEvidence.ClasspathEntry> classpath,
            List<GeneratedBytecodeContextEvidence.Lookup> lookups,
            List<GeneratedBytecodeContextEvidence.CompilerSetting> settings,
            GeneratedBytecodeContextEvidence.Completion completion) {
        return new GeneratedBytecodeContextEvidence(
                Root.class.getName(),
                hash("0"),
                hash("f"),
                classpath,
                lookups,
                settings,
                "UTF-8",
                hash("d"),
                hash("e"),
                completion);
    }

    private static List<GeneratedBytecodeContextEvidence.ClasspathEntry> classpath(String... digits) {
        List<GeneratedBytecodeContextEvidence.ClasspathEntry> result = new ArrayList<>();
        for (String digit : digits) {
            result.add(new GeneratedBytecodeContextEvidence.ClasspathEntry(
                    GeneratedBytecodeContextEvidence.ProviderKind.ARCHIVE,
                    hash(digit)));
        }
        return List.copyOf(result);
    }

    private static List<GeneratedBytecodeContextEvidence.Lookup> lookups(String... digits) {
        List<GeneratedBytecodeContextEvidence.Lookup> result = new ArrayList<>();
        for (int index = 0; index < digits.length; index++) {
            String digit = digits[index];
            result.add(GeneratedBytecodeContextEvidence.Lookup.observed(
                    index % 2 == 0
                            ? GeneratedBytecodeContextEvidence.LookupKind.SOURCE
                            : GeneratedBytecodeContextEvidence.LookupKind.RESOURCE,
                    "logical-name-" + index,
                    hash(digit),
                    GeneratedBytecodeContextEvidence.LookupOutcome.FOUND,
                    hash(Integer.toHexString((index + 8) % 16))));
        }
        return List.copyOf(result);
    }

    private static List<GeneratedBytecodeContextEvidence.CompilerSetting> settings(String key, String value) {
        return List.of(GeneratedBytecodeContextEvidence.CompilerSetting.observed(key, value));
    }

    private static String hash(String digit) {
        return digit.repeat(64);
    }

    private static LinkedHashMap<String, byte[]> completeMap() throws IOException {
        LinkedHashMap<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(Root.Inner.class.getName(), classBytes(Root.Inner.class));
        classes.put(Root.class.getName(), classBytes(Root.class));
        return classes;
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing test class resource " + resource);
            return input.readAllBytes();
        }
    }

    static final class Root {
        static final class Inner {
        }
    }

    static final class SyntheticCompilationException extends Exception {
        SyntheticCompilationException(String message) {
            super(message);
        }
    }
}
