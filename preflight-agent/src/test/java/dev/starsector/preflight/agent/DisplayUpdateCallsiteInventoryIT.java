package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Opt-in offline inventory for exact callers of LWJGL's no-argument Display.update(). */
class DisplayUpdateCallsiteInventoryIT {
    @Test
    void inventoriesExactDisplayUpdateCallersWithoutStartingStarsector() throws Exception {
        String configured = System.getProperty("preflight.starsector.callsite.jars", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.callsite.jars=<jar>[,<jar>...]");
        List<String> callers = new ArrayList<>();
        for (String value : configured.split(",")) {
            Path archive = Path.of(value.trim()).toAbsolutePath().normalize();
            Assumptions.assumeTrue(Files.isRegularFile(archive));
            try (JarFile jar = new JarFile(archive.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                    try (var input = jar.getInputStream(entry)) {
                        ClassNode owner = new ClassNode(Opcodes.ASM9);
                        new ClassReader(input).accept(owner,
                                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        for (MethodNode method : owner.methods) {
                            for (AbstractInsnNode instruction : method.instructions) {
                                if (instruction instanceof MethodInsnNode call
                                        && call.getOpcode() == Opcodes.INVOKESTATIC
                                        && "org/lwjgl/opengl/Display".equals(call.owner)
                                        && "update".equals(call.name)
                                        && "()V".equals(call.desc)) {
                                    callers.add(archive.getFileName() + ":" + owner.name + "#"
                                            + method.name + method.desc);
                                }
                            }
                        }
                    }
                }
            }
        }
        callers.forEach(caller -> System.out.println("DISPLAY_UPDATE_CALLER " + caller));
        assertFalse(callers.isEmpty(), "no exact Display.update() callsites found");
    }
}
