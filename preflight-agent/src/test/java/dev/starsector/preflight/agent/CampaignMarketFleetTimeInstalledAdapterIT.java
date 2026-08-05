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

/** Exact installed checks for sampled Market/CampaignFleet attribution; never starts the game. */
class CampaignMarketFleetTimeInstalledAdapterIT {
    @BeforeEach
    void enable() {
        CampaignMarketFleetTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        CampaignMarketFleetTimeRuntime.reset();
    }

    @Test
    void installedOwnersMatchEveryReviewedSubcall() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));
        String runtime = CampaignMarketFleetTimeRuntime.class.getName().replace('.', '/');

        byte[] market = entry(archive, CampaignMarketFleetTimePlan.MARKET_CLASS);
        ClassSignature marketSignature = ClassSignature.parse(market);
        assertEquals(CampaignMarketFleetTimePlan.MARKET_SHA256, marketSignature.sha256());
        byte[] transformedMarket = CampaignMarketFleetTimePlan.transform(marketSignature, market);
        assertNotNull(transformedMarket);
        MethodNode marketAdvance = method(transformedMarket);
        assertEquals(8, calls(marketAdvance, runtime, "enter"));
        assertEquals(3, calls(marketAdvance, runtime, "enterClass"));
        assertNull(CampaignMarketFleetTimePlan.transform(
                ClassSignature.parse(transformedMarket), transformedMarket));

        byte[] fleet = entry(archive, CampaignMarketFleetTimePlan.FLEET_CLASS);
        ClassSignature fleetSignature = ClassSignature.parse(fleet);
        assertEquals(CampaignMarketFleetTimePlan.FLEET_SHA256, fleetSignature.sha256());
        byte[] transformedFleet = CampaignMarketFleetTimePlan.transform(fleetSignature, fleet);
        assertNotNull(transformedFleet);
        MethodNode fleetAdvance = method(transformedFleet);
        assertEquals(10, calls(fleetAdvance, runtime, "enter"));
        assertEquals(3, calls(fleetAdvance, runtime, "enterClass"));
        assertNull(CampaignMarketFleetTimePlan.transform(
                ClassSignature.parse(transformedFleet), transformedFleet));
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

    private static MethodNode method(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(candidate -> CampaignMarketFleetTimePlan.ADVANCE.equals(candidate.name)
                        && CampaignMarketFleetTimePlan.DESCRIPTOR.equals(candidate.desc))
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
