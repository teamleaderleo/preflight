package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

/** Exact installed-bytecode check; it reads the game archive in memory and never starts the game. */
class CollisionQuerySetInstalledAdapterIT {
    private static final String LINKED_SET = "java/util/LinkedHashSet";
    private static final String REPLACEMENT =
            CollisionQuerySet.class.getName().replace('.', '/');

    @Test
    void installedCollisionIteratorUsesTheNodeFreeOrderedSet() throws Exception {
        String configured = System.getProperty("preflight.starsector.core.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.core.jar=<starfarer_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original = classBytes(archive, CollisionQuerySetPlan.TARGET_CLASS);
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(CollisionQuerySetPlan.ORIGINAL_SHA256, signature.sha256());
        ClassNode originalOwner = read(original);
        byte[] transformed = CollisionQuerySetPlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(CollisionQuerySetPlan.transform(ClassSignature.parse(transformed), transformed));

        ClassNode owner = read(transformed);
        verify(owner);
        MethodNode constructor = method(owner, CollisionQuerySetPlan.CONSTRUCTOR,
                CollisionQuerySetPlan.CONSTRUCTOR_DESCRIPTOR);
        MethodNode copy = method(owner, CollisionQuerySetPlan.COPY_METHOD,
                CollisionQuerySetPlan.COPY_DESCRIPTOR);
        assertEquals(0, allocations(constructor, LINKED_SET));
        assertEquals(1, allocations(constructor, REPLACEMENT));
        assertEquals(1, calls(constructor, REPLACEMENT, "<init>"));
        assertEquals(0, calls(constructor, "java/util/Set", "addAll"));
        assertEquals(1, calls(constructor, REPLACEMENT, "addAllFrom"));
        assertEquals(1, calls(constructor, "java/util/Set", "iterator"));
        assertEquals(1, calls(copy, "java/util/Set", "iterator"));
        assertEquals(originalOwner.fields.size(), owner.fields.size(),
                "the rewrite adds no game-object or save fields");
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

    private static void verify(ClassNode owner) throws Exception {
        for (MethodNode method : owner.methods) {
            new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method);
        }
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int allocations(MethodNode method, String type) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode allocation
                    && allocation.getOpcode() == Opcodes.NEW && type.equals(allocation.desc)) count++;
        }
        return count;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }
}
