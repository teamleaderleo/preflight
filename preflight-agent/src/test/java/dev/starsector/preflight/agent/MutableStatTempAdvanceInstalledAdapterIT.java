package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Opt-in structural check against Starsector's exact MutableStatWithTempMods class. */
class MutableStatTempAdvanceInstalledAdapterIT {
    @AfterEach
    void reset() {
        MutableStatTempAdvancePlan.reset();
    }

    @Test
    void installedClassUsesTheDirectBasePathAndRetainsSubclassFallback() throws Exception {
        String configured = System.getProperty("preflight.starsector.api.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.api.jar=<starfarer.api.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive),
                "configured starfarer.api.jar does not exist");

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(MutableStatTempAdvancePlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(MutableStatTempAdvancePlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = MutableStatTempAdvancePlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(MutableStatTempAdvancePlan.transform(
                ClassSignature.parse(transformed), transformed));

        ClassNode owner = read(transformed);
        MethodNode wrapper = method(owner, MutableStatTempAdvancePlan.METHOD);
        MethodNode fallback = method(owner, MutableStatTempAdvancePlan.ORIGINAL);
        assertEquals(0, calls(wrapper, MutableStatTempAdvancePlan.TARGET_CLASS, "getMods"));
        assertEquals(1, calls(wrapper, "java/util/LinkedHashMap", "isEmpty"));
        assertEquals(1, calls(wrapper, "java/util/LinkedHashMap", "values"));
        assertEquals(1, calls(wrapper, MutableStatTempAdvancePlan.TARGET_CLASS,
                MutableStatTempAdvancePlan.ORIGINAL));
        assertEquals(1, fields(wrapper, Opcodes.GETFIELD,
                MutableStatTempAdvancePlan.TARGET_CLASS, "tempMods"));
        assertEquals(2, calls(fallback, MutableStatTempAdvancePlan.TARGET_CLASS, "getMods"));
        assertTrue((fallback.access & Opcodes.ACC_PRIVATE) != 0);
        assertTrue((fallback.access & Opcodes.ACC_SYNTHETIC) != 0);

        executeRealClass(archive, transformed);
        assertNull(MutableStatTempAdvancePlan.transform(signature, changedShape(original)));

        MutableStatTempAdvancePlan.reset();
        AdapterInstallationEffects.replay(
                AdapterTargetRegistry.mutableStatTempAdvanceTarget(), signature, transformed);
        assertEquals(1L, MutableStatTempAdvancePlan.telemetry().get("installedTargets"));
    }

    private static void executeRealClass(Path archive, byte[] transformed) throws Exception {
        String targetName = MutableStatTempAdvancePlan.TARGET_CLASS.replace('/', '.');
        try (URLClassLoader loader = new ExactClassLoader(
                archive.toUri().toURL(), targetName, transformed)) {
            Class<?> type = Class.forName(targetName, true, loader);
            Object stat = type.getConstructor(float.class).newInstance(10f);
            var advance = type.getMethod("advance", float.class);
            var add = type.getMethod(
                    "addTemporaryModFlat", float.class, String.class, float.class);
            var hasMod = type.getMethod("hasMod", String.class);
            var value = type.getMethod("getModifiedValue");

            advance.invoke(stat, 1f);
            assertEquals(10f, ((Number) value.invoke(stat)).floatValue());
            add.invoke(stat, 2f, "preflight-test", 3f);
            assertTrue((Boolean) hasMod.invoke(stat, "preflight-test"));
            assertEquals(13f, ((Number) value.invoke(stat)).floatValue());
            advance.invoke(stat, 1f);
            assertTrue((Boolean) hasMod.invoke(stat, "preflight-test"));
            advance.invoke(stat, 1.1f);
            assertFalse((Boolean) hasMod.invoke(stat, "preflight-test"));
            assertEquals(10f, ((Number) value.invoke(stat)).floatValue());
        }
    }

    private static byte[] changedShape(byte[] original) {
        ClassNode owner = read(original);
        MethodNode method = method(owner, MutableStatTempAdvancePlan.METHOD);
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && MutableStatTempAdvancePlan.TARGET_CLASS.equals(call.owner)
                    && "getMods".equals(call.name)) {
                call.name = "changedGetMods";
                break;
            }
        }
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name)
                        && MutableStatTempAdvancePlan.DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }

    private static int fields(MethodNode method, int opcode, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && instruction.getOpcode() == opcode
                    && owner.equals(field.owner) && name.equals(field.name)) count++;
        }
        return count;
    }

    private static final class ExactClassLoader extends URLClassLoader {
        private final String targetName;
        private final byte[] targetBytes;

        ExactClassLoader(URL archive, String targetName, byte[] targetBytes) {
            super(new URL[] {archive}, ClassLoader.getPlatformClassLoader());
            this.targetName = targetName;
            this.targetBytes = targetBytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (targetName.equals(name)) {
                return defineClass(name, targetBytes, 0, targetBytes.length);
            }
            return super.findClass(name);
        }
    }
}
