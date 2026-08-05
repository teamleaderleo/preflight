package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Exact installed checks for campaign entity maintenance; never starts the game. */
class CampaignEntityMaintenanceInstalledAdapterIT {
    @BeforeEach
    void enable() {
        CampaignEntityMaintenanceRuntime.beginSession();
    }

    @Test
    void installedOwnersMatchReviewedShapes() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] entity = entry(archive, CampaignEntityMaintenancePlan.ENTITY_CLASS);
        ClassSignature entitySignature = ClassSignature.parse(entity);
        assertEquals(CampaignEntityMaintenancePlan.ENTITY_SHA256, entitySignature.sha256());
        assertNotNull(CampaignEntityMaintenancePlan.transform(entitySignature, entity));

        byte[] fleetView = entry(archive, CampaignEntityMaintenancePlan.FLEET_VIEW_CLASS);
        ClassSignature viewSignature = ClassSignature.parse(fleetView);
        assertEquals(CampaignEntityMaintenancePlan.FLEET_VIEW_SHA256, viewSignature.sha256());
        byte[] transformed = CampaignEntityMaintenancePlan.transform(viewSignature, fleetView);
        assertNotNull(transformed);
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        long snapshots = owner.methods.stream()
                .filter(method -> CampaignEntityMaintenancePlan.ADVANCE_METHOD.equals(method.name))
                .flatMap(method -> {
                    java.util.List<AbstractInsnNode> values = new java.util.ArrayList<>();
                    method.instructions.forEach(values::add);
                    return values.stream();
                })
                .filter(instruction -> instruction instanceof MethodInsnNode call
                        && "getSortedMembers".equals(call.name))
                .count();
        assertEquals(1L, snapshots);
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
}
