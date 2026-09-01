package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact installed shape check for the autofit phase timer; never starts the game. */
class CoreAutofitTimeInstalledAdapterIT {
    @BeforeEach
    void enable() {
        CoreAutofitTimeRuntime.beginSession(true);
    }

    @Test
    void installedOwnerMatchesReviewedSemanticRegionsAndHelperCounts() throws Exception {
        String configured = System.getProperty("preflight.starsector.api.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(), "set installed starfarer.api.jar property");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original = entry(archive, CoreAutofitTimePlan.TARGET_CLASS);
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(CoreAutofitTimePlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = CoreAutofitTimePlan.transform(signature, original);
        assertNotNull(transformed);
        MethodNode doFit = method(
                transformed, CoreAutofitTimePlan.METHOD, CoreAutofitTimePlan.DESCRIPTOR);
        assertEquals(46, calls(doFit, "enter"));
        assertEquals(46, calls(doFit, "exit"));
        List<Integer> exitPhases = exitPhases(doFit);
        assertEquals(CoreAutofitTimeRuntime.FINAL_SYNC,
                exitPhases.get(exitPhases.size() - 2));
        assertEquals(CoreAutofitTimeRuntime.TOTAL,
                exitPhases.get(exitPhases.size() - 1));
        assertNull(CoreAutofitTimePlan.transform(signature, transformed));
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

    private static MethodNode method(byte[] bytes, String name, String descriptor) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name)
                        && descriptor.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String name) {
        int result = 0;
        String runtime = CoreAutofitTimeRuntime.class.getName().replace('.', '/');
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && runtime.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }

    private static List<Integer> exitPhases(MethodNode method) {
        List<Integer> result = new ArrayList<>();
        String runtime = CoreAutofitTimeRuntime.class.getName().replace('.', '/');
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !runtime.equals(call.owner) || !"exit".equals(call.name)) continue;
            AbstractInsnNode phase = call.getPrevious().getPrevious();
            if (phase instanceof LdcInsnNode constant && constant.cst instanceof Integer value) {
                result.add(value);
            }
        }
        return result;
    }
}
