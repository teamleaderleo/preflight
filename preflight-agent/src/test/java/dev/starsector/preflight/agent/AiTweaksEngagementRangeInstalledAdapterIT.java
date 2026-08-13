package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Opt-in structural transform of AI Tweaks' exact installed class; never starts Starsector. */
class AiTweaksEngagementRangeInstalledAdapterIT {
    @Test
    void installedTargetSelectionSnapshotsOneRangeAndReusesIt() throws Exception {
        String configured = System.getProperty("preflight.aitweaks.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.aitweaks.jar=<aitweaks-core.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(AiTweaksEngagementRangePlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(AiTweaksEngagementRangePlan.ORIGINAL_SHA256, signature.sha256());
        assertEquals(61, signature.majorVersion());
        byte[] transformed = AiTweaksEngagementRangePlan.transform(signature, original);
        assertNotNull(transformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        var cache = owner.fields.stream()
                .filter(field -> "preflight$engagementRange".equals(field.name))
                .findFirst().orElseThrow();
        assertEquals("F", cache.desc);
        assertEquals("Ljava/lang/Float;", owner.fields.stream()
                .filter(field -> "preflight$engagementRangeBoxed".equals(field.name))
                .findFirst().orElseThrow().desc);
        assertEquals("Ljava/lang/Float;", owner.fields.stream()
                .filter(field -> "preflight$targetSearchRangeBoxed".equals(field.name))
                .findFirst().orElseThrow().desc);
        assertEquals(1, calls(owner,
                "com/genir/aitweaks/core/handles/WeaponHandle", "getEngagementRange-impl"));
        assertEquals(1, calls(owner,
                AiTweaksEngagementRangeRuntime.class.getName().replace('.', '/'), "snapshot"));
    }

    private static int calls(ClassNode owner, String targetOwner, String name) {
        int count = 0;
        for (var method : owner.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && targetOwner.equals(call.owner) && name.equals(call.name)) count++;
            }
        }
        return count;
    }
}
