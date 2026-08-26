package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

/** Opt-in transform check against the exact installed vanilla font wrapper. */
class FontWrapAllocationInstalledAdapterIT {
    private static final String STRING_BUILDER = "java/lang/StringBuilder";

    @AfterEach
    void reset() {
        FontWrapAllocationRuntime.beginSession();
    }

    @Test
    void installedWrapperUsesLiteralTablesAndAllocationFreeCharacterSearch() throws Exception {
        String configured = System.getProperty("preflight.fs.common.obf.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.fs.common.obf.jar=<fs.common_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] originalBytes = classBytes(archive, FontWrapAllocationPlan.TARGET_CLASS);
        ClassSignature signature = ClassSignature.parse(originalBytes);
        assertEquals(FontWrapAllocationPlan.ORIGINAL_SHA256, signature.sha256());
        ClassNode original = read(originalBytes);
        MethodNode originalMethod = method(
                original, FontWrapAllocationPlan.METHOD, FontWrapAllocationPlan.DESCRIPTOR);
        assertEquals(3, calls(originalMethod, "java/lang/String", "contains"));
        assertEquals(21, allocations(originalMethod, STRING_BUILDER));

        byte[] transformedBytes = FontWrapAllocationPlan.transform(signature, originalBytes);
        assertNotNull(transformedBytes);
        ClassNode transformed = read(transformedBytes);
        verify(transformed);
        MethodNode transformedMethod = method(
                transformed, FontWrapAllocationPlan.METHOD, FontWrapAllocationPlan.DESCRIPTOR);

        assertEquals(0, calls(transformedMethod, "java/lang/String", "contains"));
        assertEquals(3, calls(transformedMethod, "java/lang/String", "indexOf"));
        assertEquals(0, allocations(transformedMethod, STRING_BUILDER));
        assertEquals(original.fields.size(), transformed.fields.size(),
                "the font optimization adds no object or save state");
        assertEquals(calls(originalMethod, FontWrapAllocationPlan.TARGET_CLASS, "return"),
                calls(transformedMethod, FontWrapAllocationPlan.TARGET_CLASS, "return"));

        Set<String> literals = stringLiterals(transformedMethod);
        assertTrue(literals.contains("。，！？；：）］},.?!)]}"));
        assertTrue(literals.contains("[{([{("));
        assertTrue(literals.contains("）］})]}"));

        assertNull(FontWrapAllocationPlan.transform(signature, transformedBytes));
        assertTrue(AdapterTransformationRegistry.hasPlan(FontWrapAllocationRuntime.PLAN_ID));
        assertEquals(true, FontWrapAllocationRuntime.telemetry().get("installed"));
    }

    private static byte[] classBytes(Path archive, String internalName) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(internalName + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static void verify(ClassNode owner) throws Exception {
        for (MethodNode method : owner.methods) {
            new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method);
        }
    }

    private static int allocations(MethodNode method, String type) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode allocation
                    && allocation.getOpcode() == Opcodes.NEW && type.equals(allocation.desc)) {
                result++;
            }
        }
        return result;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                result++;
            }
        }
        return result;
    }

    private static Set<String> stringLiterals(MethodNode method) {
        Set<String> result = new HashSet<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode literal && literal.cst instanceof String value) {
                result.add(value);
            }
        }
        return result;
    }
}
