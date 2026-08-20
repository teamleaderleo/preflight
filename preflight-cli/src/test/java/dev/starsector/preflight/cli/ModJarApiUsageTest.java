package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModJarApiUsageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsCrossModAndStarsectorApiEvidenceWithoutCompatibilityVerdicts() throws Exception {
        Path install = temporaryDirectory.resolve("Starsector");
        Path mods = install.resolve("mods");

        Path library = mods.resolve("Library");
        metadata(library, """
                {"id":"library","jars":["jars/library.jar"]}
                """);
        jar(library.resolve("jars/library.jar"), Map.of(
                "library/Api.class", classFile("library.Api"),
                "shared/Ambiguous.class", classFile("shared.Ambiguous")));

        Path secondProvider = mods.resolve("SecondProvider");
        metadata(secondProvider, """
                {"id":"second_provider","jars":["jars/second.jar"]}
                """);
        jar(secondProvider.resolve("jars/second.jar"), Map.of(
                "shared/Ambiguous.class", classFile("shared.Ambiguous")));

        Path declared = mods.resolve("DeclaredConsumer");
        metadata(declared, """
                {
                  "id":"declared_consumer",
                  "jars":["jars/consumer.jar"],
                  "dependencies":[{"id":"library","name":"Library"}]
                }
                """);
        jar(declared.resolve("jars/consumer.jar"), Map.of(
                "consumer/Main.class", classFile(
                        "consumer.Main",
                        "library.Api",
                        "shared.Ambiguous",
                        "com.fs.starfarer.api.Global")));

        Path optional = mods.resolve("OptionalConsumer");
        metadata(optional, """
                {"id":"optional_consumer","jars":["jars/optional.jar"]}
                """);
        jar(optional.resolve("jars/optional.jar"), Map.of(
                "optional/Main.class", classFile(
                        "optional.Main",
                        "library.Api",
                        "[Lcom.fs.starfarer.api.combat.ShipAPI;")));

        enable(install, List.of("library", "second_provider", "declared_consumer", "optional_consumer"));

        ModJarApiUsage.Result first = ModJarApiUsage.scan(install);
        ModJarApiUsage.Result second = ModJarApiUsage.scan(install);
        assertEquals(first.toJson(), second.toJson());

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) first.values().get("totals");
        assertEquals(4L, count(totals, "candidateJars"));
        assertEquals(4L, count(totals, "scannedJars"));
        assertEquals(5L, count(totals, "scannedClassFiles"));
        assertEquals(0L, count(totals, "unreadableClassFiles"));
        assertEquals(2L, count(totals, "staticModEdges"));
        assertEquals(1L, count(totals, "undeclaredStaticModEdges"));
        assertEquals(2L, count(totals, "modsReferencingStarsectorApi"));
        assertEquals(1L, count(totals, "ambiguousStaticReferences"));
        assertEquals(false, totals.get("truncated"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges =
                (List<Map<String, Object>>) first.values().get("staticModReferences");
        Map<String, Object> declaredEdge = edge(edges, "declared_consumer", "library");
        assertEquals(true, declaredEdge.get("declaredDependency"));
        assertEquals(List.of("library.Api"), declaredEdge.get("sampleClasses"));

        Map<String, Object> optionalEdge = edge(edges, "optional_consumer", "library");
        assertEquals(false, optionalEdge.get("declaredDependency"));
        assertEquals(List.of("library.Api"), optionalEdge.get("sampleClasses"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apiUsage =
                (List<Map<String, Object>>) first.values().get("starsectorApiUsage");
        assertTrue(api(apiUsage, "declared_consumer").get("sampleClasses").toString()
                .contains("com.fs.starfarer.api.Global"));
        assertTrue(api(apiUsage, "optional_consumer").get("sampleClasses").toString()
                .contains("com.fs.starfarer.api.combat.ShipAPI"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ambiguous =
                (List<Map<String, Object>>) first.values().get("ambiguousStaticReferenceSamples");
        assertEquals("declared_consumer", ambiguous.get(0).get("fromMod"));
        assertEquals("shared.Ambiguous", ambiguous.get(0).get("className"));
        assertEquals(List.of("library", "second_provider"), ambiguous.get(0).get("providerMods"));

        String json = first.toJson();
        assertFalse(json.contains(temporaryDirectory.toString()));
        assertTrue(json.contains("Static bytecode evidence only"));
    }

    @Test
    void doesNotTurnBundledDuplicateClassIntoCrossModUsage() throws Exception {
        Path install = temporaryDirectory.resolve("BundledDuplicate");
        Path mods = install.resolve("mods");

        Path library = mods.resolve("Library");
        metadata(library, """
                {"id":"library","jars":["jars/library.jar"]}
                """);
        jar(library.resolve("jars/library.jar"), Map.of(
                "shared/Api.class", classFile("shared.Api")));

        Path consumer = mods.resolve("Consumer");
        metadata(consumer, """
                {"id":"consumer","jars":["jars/consumer.jar"]}
                """);
        jar(consumer.resolve("jars/consumer.jar"), Map.of(
                "consumer/Main.class", classFile("consumer.Main", "shared.Api"),
                "shared/Api.class", classFile("shared.Api")));

        enable(install, List.of("library", "consumer"));

        ModJarApiUsage.Result result = ModJarApiUsage.scan(install);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) result.values().get("totals");
        assertEquals(0L, count(totals, "staticModEdges"));
        assertEquals(1L, count(totals, "ambiguousStaticReferences"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ambiguous =
                (List<Map<String, Object>>) result.values().get("ambiguousStaticReferenceSamples");
        assertEquals("consumer", ambiguous.get(0).get("fromMod"));
        assertEquals("shared.Api", ambiguous.get(0).get("className"));
        assertEquals(List.of("library", "consumer"), ambiguous.get(0).get("providerMods"));
    }

    @Test
    void isolatesMalformedClassesAndEntryIdentityMismatches() throws Exception {
        Path install = temporaryDirectory.resolve("BrokenInstall");
        Path mod = install.resolve("mods/Broken");
        metadata(mod, """
                {"id":"broken","jars":["jars/broken.jar"]}
                """);
        jar(mod.resolve("jars/broken.jar"), Map.of(
                "broken/TooSmall.class", new byte[] {1, 2, 3},
                "broken/WrongName.class", classFile("different.Name"),
                "broken/Good.class", classFile("broken.Good", "com.fs.starfarer.api.Global")));
        enable(install, List.of("broken"));

        ModJarApiUsage.Result result = ModJarApiUsage.scan(install);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) result.values().get("totals");
        assertEquals(1L, count(totals, "scannedClassFiles"));
        assertEquals(2L, count(totals, "unreadableClassFiles"));
        assertEquals(1L, count(totals, "modsReferencingStarsectorApi"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jarScans = (List<Map<String, Object>>) result.values().get("jarScans");
        assertEquals(2L, ((Number) jarScans.get(0).get("unreadableClassFiles")).longValue());
        assertEquals(2, ((List<?>) jarScans.get(0).get("errors")).size());
        assertFalse((Boolean) jarScans.get(0).get("truncated"));
    }

    private static long count(Map<String, Object> totals, String key) {
        return ((Number) totals.get(key)).longValue();
    }

    private static Map<String, Object> edge(List<Map<String, Object>> edges, String from, String to) {
        return edges.stream()
                .filter(edge -> from.equals(edge.get("fromMod")) && to.equals(edge.get("toMod")))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> api(List<Map<String, Object>> usage, String modId) {
        return usage.stream()
                .filter(entry -> modId.equals(entry.get("modId")))
                .findFirst()
                .orElseThrow();
    }

    private static byte[] classFile(String binaryName, String... references) throws Exception {
        LinkedHashSet<String> classes = new LinkedHashSet<>();
        classes.add(binaryName);
        classes.add("java.lang.Object");
        classes.addAll(List.of(references));
        List<String> ordered = new ArrayList<>(classes);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0xcafebabe);
            output.writeShort(0);
            output.writeShort(52);
            output.writeShort(1 + ordered.size() * 2);
            for (int index = 0; index < ordered.size(); index++) {
                String value = ordered.get(index);
                String internal = value.startsWith("[") ? value : value.replace('.', '/');
                int utf8Index = 1 + index * 2;
                output.writeByte(1);
                output.writeUTF(internal);
                output.writeByte(7);
                output.writeShort(utf8Index);
            }
            output.writeShort(0x0021);
            output.writeShort(2);
            output.writeShort(4);
            output.writeShort(0);
            output.writeShort(0);
            output.writeShort(0);
            output.writeShort(0);
        }
        return bytes.toByteArray();
    }

    private static void metadata(Path mod, String value) throws Exception {
        Files.createDirectories(mod);
        Files.writeString(mod.resolve("mod_info.json"), value);
    }

    private static void enable(Path install, List<String> ids) throws Exception {
        Files.createDirectories(install.resolve("mods"));
        String enabled = ids.stream().map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        Files.writeString(install.resolve("mods/enabled_mods.json"), "{\"enabledMods\":[" + enabled + "]}");
    }

    private static void jar(Path path, Map<String, byte[]> entries) throws Exception {
        Files.createDirectories(path.getParent());
        Map<String, byte[]> ordered = new LinkedHashMap<>();
        entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                ordered.put(entry.getKey(), entry.getValue()));
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : ordered.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }
}
