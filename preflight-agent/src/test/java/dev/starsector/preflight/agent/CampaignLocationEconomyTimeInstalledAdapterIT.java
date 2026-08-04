package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact installed checks for the deeper campaign attribution probe; never starts the game. */
class CampaignLocationEconomyTimeInstalledAdapterIT {
    @BeforeEach
    void enable() {
        CampaignLocationEconomyTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        CampaignLocationEconomyTimeRuntime.reset();
    }

    @Test
    void installedLocationAndEconomyMatchReviewedCalls() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));
        String runtime = CampaignLocationEconomyTimeRuntime.class.getName().replace('.', '/');

        byte[] location = entry(archive, CampaignLocationEconomyTimePlan.LOCATION_CLASS);
        ClassSignature locationSignature = ClassSignature.parse(location);
        assertEquals(CampaignLocationEconomyTimePlan.LOCATION_SHA256, locationSignature.sha256());
        byte[] indexedLocation = EntityLookupPlan.transform(locationSignature, location);
        assertNotNull(indexedLocation);
        byte[] composedLocation = CampaignLocationEconomyTimePlan.transform(
                locationSignature, indexedLocation);
        assertNotNull(composedLocation);
        assertEquals(1, calls(method(composedLocation,
                CampaignLocationEconomyTimePlan.LOCATION_ADVANCE), runtime, "enterClass"));
        assertEquals(1, calls(method(composedLocation, EntityLookupPlan.LOOKUP_METHOD),
                EntityLookupRuntime.class.getName().replace('.', '/'), "lookup"));

        byte[] transformedLocation = CampaignLocationEconomyTimePlan.transform(
                locationSignature, location);
        assertNotNull(transformedLocation);
        assertNull(CampaignLocationEconomyTimePlan.transform(
                ClassSignature.parse(transformedLocation), transformedLocation));
        assertEquals(1, calls(method(transformedLocation,
                CampaignLocationEconomyTimePlan.LOCATION_ADVANCE), runtime, "enterClass"));
        assertEquals(2, calls(method(transformedLocation,
                CampaignLocationEconomyTimePlan.LOCATION_PAUSED), runtime, "enterClass"));

        byte[] economy = entry(archive, CampaignLocationEconomyTimePlan.ECONOMY_CLASS);
        ClassSignature economySignature = ClassSignature.parse(economy);
        assertEquals(CampaignLocationEconomyTimePlan.ECONOMY_SHA256, economySignature.sha256());
        byte[] transformedEconomy = CampaignLocationEconomyTimePlan.transform(
                economySignature, economy);
        assertNotNull(transformedEconomy);
        assertEquals(3, calls(method(transformedEconomy,
                CampaignLocationEconomyTimePlan.ECONOMY_ADVANCE), runtime, "enter"));
    }

    private static byte[] entry(Path archive, String name) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(name + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static MethodNode method(byte[] bytes, String name) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream().filter(candidate -> name.equals(candidate.name))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }
}
