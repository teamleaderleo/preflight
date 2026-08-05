package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class AshLibVariantLookupPlanTest {
    private static final String RUNTIME =
            AshLibVariantLookupRuntime.class.getName().replace('.', '/');

    @BeforeEach
    void reset() {
        AshLibVariantLookupRuntime.beginSession();
    }

    @Test
    void repositoryBracketsCallbackAndLookupReturnsOnlyInsideScope() {
        byte[] repository = AshLibVariantLookupPlan.transform(
                repositorySignature(), repositoryFixture());
        byte[] lookup = AshLibVariantLookupPlan.transform(lookupSignature(), lookupFixture());
        assertNotNull(repository);
        assertNotNull(lookup);

        MethodNode populate = method(read(repository),
                AshLibVariantLookupPlan.POPULATE,
                AshLibVariantLookupPlan.POPULATE_DESCRIPTOR);
        assertEquals(1, calls(populate, RUNTIME, "begin"));
        assertEquals(2, calls(populate, RUNTIME, "end"));
        MethodNode getVariant = method(read(lookup),
                AshLibVariantLookupPlan.LOOKUP,
                AshLibVariantLookupPlan.LOOKUP_DESCRIPTOR);
        assertEquals(1, calls(getVariant, RUNTIME, "active"));
        assertEquals(1, calls(getVariant, RUNTIME, "lookup"));
        assertEquals(true, AshLibVariantLookupRuntime.telemetry().get("repositoryInstalled"));
        assertEquals(true, AshLibVariantLookupRuntime.telemetry().get("lookupInstalled"));
    }

    @Test
    void wrongHashAndSecondRewriteFailClosed() {
        ClassSignature repository = repositorySignature();
        assertNull(AshLibVariantLookupPlan.transform(new ClassSignature(
                repository.internalName(), "0".repeat(64), repository.majorVersion(),
                repository.access(), repository.methods()), repositoryFixture()));
        byte[] once = AshLibVariantLookupPlan.transform(repository, repositoryFixture());
        assertNotNull(once);
        assertNull(AshLibVariantLookupPlan.transform(repository, once));
    }

    @Test
    void targetsPinReviewedAshLibArchiveAndPlan() {
        for (AdapterTarget target : List.of(
                AdapterTargetRegistry.ashLibVariantRepositoryTarget(),
                AdapterTargetRegistry.ashLibVariantLookupTarget())) {
            assertTrue(AdapterTransformationRegistry.hasPlan(target.planId()));
            AdapterSourceIdentity source = new AdapterSourceIdentity(
                    "file:/game/mods/AshLib/jars/ashlib.jar",
                    "/game/mods/ashlib/jars/ashlib.jar", "MOD", target.sourceSha256(), "",
                    "java/net/URLClassLoader", "");
            ClassSignature signature = target.internalClassName().equals(
                    AshLibVariantLookupPlan.REPOSITORY_CLASS)
                    ? repositorySignature() : lookupSignature();
            assertTrue(target.match(signature, source).exact());
        }
    }

    private static ClassSignature repositorySignature() {
        return new ClassSignature(
                AshLibVariantLookupPlan.REPOSITORY_CLASS,
                AshLibVariantLookupPlan.REPOSITORY_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(
                        AshLibVariantLookupPlan.POPULATE,
                        AshLibVariantLookupPlan.POPULATE_DESCRIPTOR,
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)));
    }

    private static ClassSignature lookupSignature() {
        return new ClassSignature(
                AshLibVariantLookupPlan.LOOKUP_CLASS,
                AshLibVariantLookupPlan.LOOKUP_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(
                        AshLibVariantLookupPlan.LOOKUP,
                        AshLibVariantLookupPlan.LOOKUP_DESCRIPTOR,
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)));
    }

    private static byte[] repositoryFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                AshLibVariantLookupPlan.REPOSITORY_CLASS, null, "java/lang/Object", null);
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                AshLibVariantLookupPlan.POPULATE,
                AshLibVariantLookupPlan.POPULATE_DESCRIPTOR,
                null,
                null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 0;
        method.maxLocals = 0;
        method.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] lookupFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                AshLibVariantLookupPlan.LOOKUP_CLASS, null, "java/lang/Object", null);
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                AshLibVariantLookupPlan.LOOKUP,
                AshLibVariantLookupPlan.LOOKUP_DESCRIPTOR,
                null,
                null);
        method.instructions.add(new LdcInsnNode("vanilla"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        method.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
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

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }
}
