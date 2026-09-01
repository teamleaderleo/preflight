package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterSourceIdentityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void capturesPortableSourceKindSuffixLoaderAndOptionalHash() throws Exception {
        Path archive = temporaryDirectory.resolve("Starsector/starsector-core/starfarer_obf.jar");
        Files.createDirectories(archive.getParent());
        byte[] content = "synthetic-core-archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(archive, content);
        ProtectionDomain domain = domain(archive);

        try (URLClassLoader loader = new URLClassLoader(
                "starsector-loader", new URL[0], getClass().getClassLoader())) {
            AdapterSourceIdentity withoutHash = AdapterSourceIdentity.capture(loader, domain, false);
            assertEquals("STARSECTOR_CORE", withoutHash.sourceKind());
            assertTrue(withoutHash.sourceEndsWith("starsector-core\\starfarer_obf.jar"));
            assertEquals("java/net/URLClassLoader", withoutHash.loaderClass());
            assertEquals("starsector-loader", withoutHash.loaderName());
            assertEquals("", withoutHash.sourceSha256());
            assertEquals("", withoutHash.sourceHashProblem());

            AdapterSourceIdentity hashed = AdapterSourceIdentity.capture(loader, domain, true);
            assertEquals(sha256(content), hashed.sourceSha256());
            assertEquals("", hashed.sourceHashProblem());
            assertEquals(withoutHash.normalizedSource(), hashed.normalizedSource());
        }
    }

    @Test
    void recognizesTheReviewedCommonArchiveByExactFileName() throws Exception {
        Path archive = temporaryDirectory.resolve("isolated/contents/resources/java/fs.common_obf.jar");
        Files.createDirectories(archive.getParent());
        Files.write(archive, new byte[] {1});

        AdapterSourceIdentity identity = AdapterSourceIdentity.capture(
                getClass().getClassLoader(), domain(archive), false);

        assertEquals("STARSECTOR_CORE", identity.sourceKind());
        assertTrue(identity.sourceEndsWith("contents/resources/java/fs.common_obf.jar"));
    }

    @Test
    void distinguishesFastRenderingModsAndUnknownSources() throws Exception {
        Path fastRendering = temporaryDirectory.resolve("game/mods/Fast-Rendering/fast-rendering.jar");
        Path ordinaryMod = temporaryDirectory.resolve("game/mods/Example/example.jar");
        Files.createDirectories(fastRendering.getParent());
        Files.createDirectories(ordinaryMod.getParent());
        Files.write(fastRendering, new byte[] {1});
        Files.write(ordinaryMod, new byte[] {2});

        assertEquals("FAST_RENDERING", AdapterSourceIdentity.capture(
                getClass().getClassLoader(), domain(fastRendering), false).sourceKind());
        assertEquals("MOD", AdapterSourceIdentity.capture(
                getClass().getClassLoader(), domain(ordinaryMod), false).sourceKind());
        assertEquals("UNKNOWN", AdapterSourceIdentity.capture(null, null, false).sourceKind());
        assertEquals("<bootstrap>", AdapterSourceIdentity.capture(null, null, false).loaderClass());
    }

    @Test
    void sourceHashFailuresRemainDiagnosticAndFailClosed() throws Exception {
        Path directory = temporaryDirectory.resolve("starsector-core/not-an-archive.jar");
        Files.createDirectories(directory);
        AdapterSourceIdentity identity = AdapterSourceIdentity.capture(
                getClass().getClassLoader(), domain(directory), true);

        assertEquals("", identity.sourceSha256());
        assertFalse(identity.sourceHashProblem().isBlank());
    }

    @Test
    @SuppressWarnings("deprecation") // File.toURL is what the game calls; see below.
    void hashesLiveModFileUrlsWhoseSpacesWereNotEscaped() throws Exception {
        Path archive = temporaryDirectory.resolve("game/mods/zz GraphicsLib-1.12.1/jars/Graphics.jar");
        Files.createDirectories(archive.getParent());
        byte[] content = "graphicslib-archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(archive, content);
        // Built the way ScriptStore builds it: File.toURL, feeding a URLClassLoader. That method is
        // deprecated precisely because it does not escape, which is what puts a raw space in the URL
        // this test exists for. Concatenating "file:" with the path instead would agree with it on
        // Unix and diverge on Windows, where toURL replaces the separators and prepends a slash --
        // posing a "C:\..." URL the game never emits and failing on a shape it never sees.
        URL rawFileUrl = archive.toAbsolutePath().toFile().toURL();
        assertTrue(rawFileUrl.toString().contains(" "));
        assertTrue(rawFileUrl.getPath().startsWith("/"), rawFileUrl.toString());

        ProtectionDomain domain = new ProtectionDomain(
                new CodeSource(rawFileUrl, (Certificate[]) null), null);
        AdapterSourceIdentity identity = AdapterSourceIdentity.capture(
                getClass().getClassLoader(), domain, true);

        assertEquals("MOD", identity.sourceKind());
        assertTrue(identity.sourceEndsWith("zz GraphicsLib-1.12.1/jars/Graphics.jar"));
        assertEquals(sha256(content), identity.sourceSha256());
        assertEquals("", identity.sourceHashProblem());
    }

    @Test
    void recoversUnambiguousSourceFromSingleUrlLoaderWithoutProtectionDomain() throws Exception {
        Path archive = temporaryDirectory.resolve("game/mods/AI Tweaks/jars/aitweaks-core.jar");
        Path other = temporaryDirectory.resolve("game/mods/AI Tweaks/jars/other.jar");
        Files.createDirectories(archive.getParent());
        byte[] content = "ai-tweaks-core".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(archive, content);
        Files.write(other, new byte[] {1});

        try (URLClassLoader one = new URLClassLoader(
                    new URL[] {archive.toUri().toURL()}, getClass().getClassLoader());
                URLClassLoader ambiguous = new URLClassLoader(
                    new URL[] {archive.toUri().toURL(), other.toUri().toURL()},
                    getClass().getClassLoader())) {
            AdapterSourceIdentity recovered = AdapterSourceIdentity.capture(one, null, true);
            assertEquals("MOD", recovered.sourceKind());
            assertTrue(recovered.sourceEndsWith("AI Tweaks/jars/aitweaks-core.jar"));
            assertEquals(sha256(content), recovered.sourceSha256());

            AdapterSourceIdentity refused = AdapterSourceIdentity.capture(ambiguous, null, true);
            assertEquals("UNKNOWN", refused.sourceKind());
            assertEquals("", refused.normalizedSource());
            assertFalse(refused.sourceHashProblem().isBlank());
        }
    }

    @Test
    void identicalClassBytesFromDifferentSourcesRemainDistinctInReport() throws Exception {
        ClassSignature signature = ClassSignature.parse(classBytes());
        AdapterSourceIdentity first = new AdapterSourceIdentity(
                "file:/one/starsector-core/core.jar",
                "/one/starsector-core/core.jar",
                "STARSECTOR_CORE",
                "",
                "",
                "loader/One",
                "one");
        AdapterSourceIdentity second = new AdapterSourceIdentity(
                "file:/two/mods/copy.jar",
                "/two/mods/copy.jar",
                "MOD",
                "",
                "",
                "loader/Two",
                "two");
        Path reportPath = temporaryDirectory.resolve("adapter.json");
        AdapterReport report = new AdapterReport(
                AdapterMode.PROBE, reportPath, null, List.of("dev/starsector/"));
        report.observed(signature, first);
        report.observed(signature, second);
        report.write();

        String json = Files.readString(reportPath);
        assertTrue(json.contains("\"retainedCandidates\":2"), json);
        assertTrue(json.contains("\"loaderClass\":\"loader/One\""), json);
        assertTrue(json.contains("\"loaderClass\":\"loader/Two\""), json);
        assertTrue(json.contains("/one/starsector-core/core.jar"), json);
        assertTrue(json.contains("/two/mods/copy.jar"), json);
    }

    private static ProtectionDomain domain(Path path) throws Exception {
        return new ProtectionDomain(
                new CodeSource(path.toUri().toURL(), (Certificate[]) null),
                null);
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static byte[] classBytes() throws Exception {
        String resource = "/" + AdapterSourceIdentityTest.class.getName().replace('.', '/') + ".class";
        try (var input = AdapterSourceIdentityTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing " + resource);
            }
            return input.readAllBytes();
        }
    }
}
