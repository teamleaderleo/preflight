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
import org.objectweb.asm.tree.MethodNode;

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

        byte[] composed = AdapterTransformationRegistry.transform(
                AdapterTargetRegistry.campaignEntityIdMutationTarget(), entitySignature, entity);
        assertNotNull(composed);
        ClassNode entityOwner = read(composed);
        assertNotNull(method(entityOwner, CampaignEntityMaintenancePlan.SCRIPT_METHOD,
                CampaignEntityMaintenancePlan.SCRIPT_DESCRIPTOR));
        assertNotNull(method(entityOwner, "preflight$original$runScripts",
                CampaignEntityMaintenancePlan.SCRIPT_DESCRIPTOR));
        assertEquals(1L, calls(method(entityOwner, EntityIdMutationPlan.SET_ID_METHOD,
                EntityIdMutationPlan.SET_ID_DESCRIPTOR),
                "dev/starsector/preflight/agent/EntityLookupRuntime", "entityIdChanging"));

        byte[] fleetView = entry(archive, CampaignEntityMaintenancePlan.FLEET_VIEW_CLASS);
        ClassSignature viewSignature = ClassSignature.parse(fleetView);
        assertEquals(CampaignEntityMaintenancePlan.FLEET_VIEW_SHA256, viewSignature.sha256());
        byte[] transformed = CampaignEntityMaintenancePlan.transform(viewSignature, fleetView);
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
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

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static long calls(MethodNode method, String owner, String name) {
        long result = 0L;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
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
