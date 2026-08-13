package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Opt-in transform check against the exact installed Logistics Notifications tracker. */
class LogisticsNotificationsFuelInstalledAdapterIT {
    @AfterEach
    void reset() {
        LogisticsNotificationsFuelRuntime.reset();
    }

    @Test
    void installedTrackerInitializesFuelBeforeItsConstructorReturns() throws Exception {
        String configured = System.getProperty("preflight.logisticsNotifications.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.logisticsNotifications.jar=<LogNot.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(LogisticsNotificationsFuelPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(LogisticsNotificationsFuelPlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = LogisticsNotificationsFuelPlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(LogisticsNotificationsFuelPlan.transform(signature, transformed));

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode constructor = owner.methods.stream()
                .filter(method -> "<init>".equals(method.name) && "()V".equals(method.desc))
                .findFirst().orElseThrow();
        int calls = 0;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && LogisticsNotificationsFuelPlan.TARGET_CLASS.equals(call.owner)
                    && "updateFuelLY".equals(call.name) && "()V".equals(call.desc)) calls++;
        }
        assertEquals(1, calls);
    }
}
