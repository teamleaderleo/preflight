package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterSignatureGateTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesDeterministicClassAndMethodSignatures() throws Exception {
        byte[] bytes = classBytes();
        ClassSignature first = ClassSignature.parse(bytes);
        ClassSignature second = ClassSignature.parse(bytes);

        assertEquals("dev/starsector/preflight/agent/AdapterSignatureGateTest", first.internalName());
        assertEquals(first, second);
        assertEquals(61, first.majorVersion());
        assertTrue(first.hasMethod("parsesDeterministicClassAndMethodSignatures", "()V"));
        assertThrows(IOException.class, () -> ClassSignature.parse(new byte[] {0, 1, 2, 3}));
    }

    @Test
    void targetRequiresExactClassHashMethodsSourceAndLoader() throws Exception {
        ClassSignature signature = ClassSignature.parse(classBytes());
        byte[] archiveBytes = "source archive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sourceHash = sha256(archiveBytes);
        String loaderClass = getClass().getClassLoader().getClass().getName().replace('.', '/');
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                "file:/game/starsector-core/starfarer_obf.jar",
                "/game/starsector-core/starfarer_obf.jar",
                "STARSECTOR_CORE",
                sourceHash,
                "",
                loaderClass,
                "app");
        AdapterTarget exact = new AdapterTarget(
                "synthetic",
                signature.internalName(),
                signature.sha256(),
                "synthetic-plan",
                List.of(new AdapterTarget.RequiredMethod(
                        "targetRequiresExactClassHashMethodsSourceAndLoader", "()V")),
                "STARSECTOR_CORE",
                "starsector-core/starfarer_obf.jar",
                sourceHash,
                loaderClass,
                "app");
        assertTrue(exact.match(signature, source).exact());
        assertTrue(exact.hasLiveSourceBinding());

        AdapterTarget wrongHash = new AdapterTarget(
                "wrong-hash",
                signature.internalName(),
                "0".repeat(64),
                "synthetic-plan",
                exact.requiredMethods(),
                exact.sourceKind(),
                exact.sourceSuffix(),
                exact.sourceSha256(),
                exact.loaderClass(),
                exact.loaderName());
        assertFalse(wrongHash.match(signature, source).exact());

        AdapterTarget wrongSource = new AdapterTarget(
                "wrong-source",
                signature.internalName(),
                signature.sha256(),
                "synthetic-plan",
                exact.requiredMethods(),
                "MOD",
                "mods/copy.jar",
                "f".repeat(64),
                "loader/Other",
                "other");
        AdapterTarget.Match mismatch = wrongSource.match(signature, source);
        assertFalse(mismatch.exact());
        assertTrue(mismatch.problems().stream().anyMatch(value -> value.contains("source kind")));
        assertTrue(mismatch.problems().stream().anyMatch(value -> value.contains("code-source suffix")));
        assertTrue(mismatch.problems().stream().anyMatch(value -> value.contains("code-source SHA-256")));
        assertTrue(mismatch.problems().stream().anyMatch(value -> value.contains("classloader class")));
        assertTrue(mismatch.problems().stream().anyMatch(value -> value.contains("classloader name")));
    }

    @Test
    void probeAndEnabledModesRetainOriginalBytesForBoundTargetWithoutRegisteredPlan() throws Exception {
        byte[] bytes = classBytes();
        ClassSignature signature = ClassSignature.parse(bytes);
        Path archive = temporaryDirectory.resolve("Starsector/starsector-core/starfarer_obf.jar");
        Files.createDirectories(archive.getParent());
        Files.write(archive, "archive".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ProtectionDomain domain = domain(archive);
        ClassLoader loader = getClass().getClassLoader();
        String loaderClass = loader.getClass().getName().replace('.', '/');
        Path targetFile = temporaryDirectory.resolve("targets.txt");
        Files.writeString(targetFile, """
                target synthetic
                class %s
                sha256 %s
                plan synthetic-plan
                source-kind STARSECTOR_CORE
                source-suffix starsector-core/starfarer_obf.jar
                loader-class %s
                method probeAndEnabledModesRetainOriginalBytesForBoundTargetWithoutRegisteredPlan ()V
                end
                """.formatted(signature.internalName(), signature.sha256(), loaderClass));
        AdapterTargetRegistry registry = AdapterTargetRegistry.load(targetFile);
        AdapterTarget parsed = registry.targets().get(0);
        assertEquals("STARSECTOR_CORE", parsed.sourceKind());
        assertEquals("starsector-core/starfarer_obf.jar", parsed.sourceSuffix());
        assertEquals(loaderClass, parsed.loaderClass());

        Path probeReportPath = temporaryDirectory.resolve("probe.json");
        AdapterReport probeReport = new AdapterReport(
                AdapterMode.PROBE, probeReportPath, targetFile, List.of("dev/starsector/"));
        AdapterProbeTransformer probe = new AdapterProbeTransformer(
                AdapterMode.PROBE, registry, List.of("dev/starsector/"), probeReport);
        assertNull(probe.transform(loader, signature.internalName(), null, domain, bytes));
        probeReport.write();
        String probeJson = Files.readString(probeReportPath);
        assertTrue(probeJson.contains("\"exactMatches\":1"), probeJson);
        assertTrue(probeJson.contains("\"normalizedSource\":"), probeJson);
        assertTrue(probeJson.contains("\"loaderClass\":\"" + loaderClass + "\""), probeJson);
        assertTrue(probeJson.contains("\"transformationsApplied\":0"), probeJson);

        Path enabledReportPath = temporaryDirectory.resolve("enabled.json");
        AdapterReport enabledReport = new AdapterReport(
                AdapterMode.ENABLED, enabledReportPath, targetFile, List.of("dev/starsector/"));
        AdapterProbeTransformer enabled = new AdapterProbeTransformer(
                AdapterMode.ENABLED, registry, List.of("dev/starsector/"), enabledReport);
        assertNull(enabled.transform(loader, signature.internalName(), null, domain, bytes));
        enabledReport.write();
        String enabledJson = Files.readString(enabledReportPath);
        assertTrue(enabledJson.contains("\"transformationEligible\":1"), enabledJson);
        assertTrue(enabledJson.contains("unavailable for this session"), enabledJson);
        assertTrue(enabledJson.contains("\"sourceBindingRejected\":0"), enabledJson);
        assertTrue(enabledJson.contains("\"transformationsApplied\":0"), enabledJson);
    }

    @Test
    void enabledModeRejectsExactClassWithoutRequiredLiveSourceBinding() throws Exception {
        byte[] bytes = classBytes();
        ClassSignature signature = ClassSignature.parse(bytes);
        Path targetFile = temporaryDirectory.resolve("unbound-targets.txt");
        Files.writeString(targetFile, """
                target unbound
                class %s
                sha256 %s
                plan synthetic-plan
                method enabledModeRejectsExactClassWithoutRequiredLiveSourceBinding ()V
                end
                """.formatted(signature.internalName(), signature.sha256()));
        AdapterTargetRegistry registry = AdapterTargetRegistry.load(targetFile);
        AdapterReport report = new AdapterReport(
                AdapterMode.ENABLED,
                temporaryDirectory.resolve("unbound.json"),
                targetFile,
                List.of("dev/starsector/"));
        AdapterProbeTransformer transformer = new AdapterProbeTransformer(
                AdapterMode.ENABLED, registry, List.of("dev/starsector/"), report);

        assertNull(transformer.transform(
                getClass().getClassLoader(), signature.internalName(), null, null, bytes));
        report.write();
        String json = Files.readString(temporaryDirectory.resolve("unbound.json"));
        assertTrue(json.contains("\"exactMatches\":1"), json);
        assertTrue(json.contains("\"sourceBindingRejected\":1"), json);
        assertTrue(json.contains("\"transformationEligible\":0"), json);
        assertTrue(json.contains("lacks required live source bindings"), json);
    }

    @Test
    void sourceShaDirectiveIsParsedAndValidated() throws Exception {
        ClassSignature signature = ClassSignature.parse(classBytes());
        String sourceHash = "a".repeat(64);
        Path targetFile = temporaryDirectory.resolve("source-hash-target.txt");
        Files.writeString(targetFile, """
                target source-hash
                class %s
                sha256 %s
                plan synthetic-plan
                source-kind STARSECTOR_CORE
                source-suffix starsector-core/core.jar
                source-sha256 %s
                loader-class example/Loader
                loader-name named-loader
                method parsesDeterministicClassAndMethodSignatures ()V
                end
                """.formatted(signature.internalName(), signature.sha256(), sourceHash));
        AdapterTarget target = AdapterTargetRegistry.load(targetFile).targets().get(0);
        assertTrue(target.requiresSourceHash());
        assertTrue(target.hasLiveSourceBinding());
        assertEquals(sourceHash, target.sourceSha256());
        assertEquals("named-loader", target.loaderName());

        Files.writeString(targetFile, Files.readString(targetFile).replace(sourceHash, "bad"));
        IOException invalid = assertThrows(IOException.class, () -> AdapterTargetRegistry.load(targetFile));
        assertTrue(invalid.getMessage().contains("source SHA-256"), invalid.getMessage());
    }

    @Test
    void emptyRegistryAndMalformedClassesRemainNonfatal() throws Exception {
        Path reportPath = temporaryDirectory.resolve("empty.json");
        AdapterReport report = new AdapterReport(
                AdapterMode.ENABLED, reportPath, null, List.of("dev/starsector/"));
        AdapterProbeTransformer transformer = new AdapterProbeTransformer(
                AdapterMode.ENABLED,
                AdapterTargetRegistry.empty(),
                List.of("dev/starsector/"),
                report);
        assertNull(transformer.transform(null, "dev/starsector/Broken", null, null, new byte[] {1, 2, 3}));
        assertNull(transformer.transform(null, "other/Unrelated", null, null, classBytes()));
        report.write();
        String json = Files.readString(reportPath);
        assertTrue(json.contains("\"malformedClasses\":1"), json);
        assertTrue(json.contains("\"transformationsApplied\":0"), json);
    }

    @Test
    void registryRejectsDuplicateIdsAndOversizedLines() throws Exception {
        ClassSignature signature = ClassSignature.parse(classBytes());
        String target = """
                target duplicate
                class %s
                sha256 %s
                method parsesDeterministicClassAndMethodSignatures ()V
                end
                """.formatted(signature.internalName(), signature.sha256());
        Path duplicates = temporaryDirectory.resolve("duplicates.txt");
        Files.writeString(duplicates, target + target);
        IOException duplicate = assertThrows(IOException.class, () -> AdapterTargetRegistry.load(duplicates));
        assertTrue(duplicate.getMessage().contains("Duplicate target ID"), duplicate.getMessage());

        Path oversizedLine = temporaryDirectory.resolve("oversized-line.txt");
        Files.writeString(oversizedLine, "#" + "x".repeat(4_096) + System.lineSeparator());
        IOException oversized = assertThrows(IOException.class, () -> AdapterTargetRegistry.load(oversizedLine));
        assertTrue(oversized.getMessage().contains("Line exceeds"), oversized.getMessage());
    }

    @Test
    void killSwitchAcceptsPropertyOrEnvironment() {
        Properties properties = new Properties();
        assertFalse(AdapterRuntime.killSwitch(Map.of(), properties));
        properties.setProperty("preflight.adapter.disabled", "true");
        assertTrue(AdapterRuntime.killSwitch(Map.of(), properties));
        assertTrue(AdapterRuntime.killSwitch(Map.of("PREFLIGHT_DISABLE_ADAPTER", "1"), new Properties()));
    }

    @Test
    void diagnosticPlanFilterParsesAndOmitsEveryTargetForAPlan() {
        Properties properties = new Properties();
        properties.setProperty(AdapterRuntime.DISABLED_PLANS_PROPERTY,
                " prepared-audio-v1, campaign-entity-index-v3,prepared-audio-v1 ");
        Set<String> disabled = AdapterRuntime.disabledPlans(properties);
        assertEquals(Set.of("prepared-audio-v1", "campaign-entity-index-v3"), disabled);

        AdapterTargetRegistry registry = AdapterTargetRegistry.empty()
                .withTextureTarget(TextureAdapterMode.PREPARED_PIXELS)
                .withoutPlans(disabled);
        assertTrue(registry.targets().stream()
                .noneMatch(target -> disabled.contains(target.planId())));
        assertTrue(registry.targets().stream()
                .anyMatch(target -> SimOpponentSafetyRuntime.PLAN_ID.equals(target.planId())));
    }

    private static ProtectionDomain domain(Path path) throws Exception {
        return new ProtectionDomain(
                new CodeSource(path.toUri().toURL(), (Certificate[]) null),
                null);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] classBytes() throws IOException {
        String resource = "/" + AdapterSignatureGateTest.class.getName().replace('.', '/') + ".class";
        try (InputStream input = AdapterSignatureGateTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing test class resource " + resource);
            }
            return input.readAllBytes();
        }
    }
}
