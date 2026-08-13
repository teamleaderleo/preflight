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
    void privateReadOnlyShipJsonLoaderIsMemoizedWithinTheSameScope() {
        byte[] transformed = AshLibVariantLookupPlan.transform(
                shipJsonSignature(), shipJsonFixture());
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        MethodNode wrapper = method(owner,
                AshLibVariantLookupPlan.SHIP_JSON_METHOD,
                AshLibVariantLookupPlan.SHIP_JSON_DESCRIPTOR);
        assertEquals(1, calls(wrapper, RUNTIME, "cachedShipJson"));
        assertEquals(1, calls(wrapper, RUNTIME, "rememberShipJson"));
        assertNotNull(method(owner, "preflight$original$getShipJson",
                AshLibVariantLookupPlan.SHIP_JSON_DESCRIPTOR));
        assertEquals(true, AshLibVariantLookupRuntime.telemetry().get("shipJsonInstalled"));
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
                AdapterTargetRegistry.ashLibVariantLookupTarget(),
                AdapterTargetRegistry.ashLibShipJsonTarget())) {
            assertTrue(AdapterTransformationRegistry.hasPlan(target.planId()));
            AdapterSourceIdentity source = new AdapterSourceIdentity(
                    "file:/game/mods/AshLib/jars/ashlib.jar",
                    "/game/mods/ashlib/jars/ashlib.jar", "MOD", target.sourceSha256(), "",
                    "java/net/URLClassLoader", "");
            ClassSignature signature;
            if (target.internalClassName().equals(AshLibVariantLookupPlan.REPOSITORY_CLASS)) {
                signature = repositorySignature();
            } else if (target.internalClassName().equals(AshLibVariantLookupPlan.LOOKUP_CLASS)) {
                signature = lookupSignature();
            } else {
                signature = shipJsonSignature();
            }
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

    private static ClassSignature shipJsonSignature() {
        return new ClassSignature(
                AshLibVariantLookupPlan.SHIP_JSON_CLASS,
                AshLibVariantLookupPlan.SHIP_JSON_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(
                        AshLibVariantLookupPlan.SHIP_JSON_METHOD,
                        AshLibVariantLookupPlan.SHIP_JSON_DESCRIPTOR,
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)));
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

    private static byte[] shipJsonFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                AshLibVariantLookupPlan.SHIP_JSON_CLASS, null, "java/lang/Object", null);
        MethodNode loader = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                AshLibVariantLookupPlan.SHIP_JSON_METHOD,
                AshLibVariantLookupPlan.SHIP_JSON_DESCRIPTOR,
                null,
                new String[] {"java/io/IOException"});
        loader.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        loader.instructions.add(new InsnNode(Opcodes.ARETURN));
        loader.maxStack = 1;
        loader.maxLocals = 1;
        loader.accept(writer);
        for (int index = 0; index < 4; index++) {
            MethodNode caller = new MethodNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "caller" + index,
                    AshLibVariantLookupPlan.SHIP_JSON_DESCRIPTOR,
                    null,
                    null);
            caller.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
            caller.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    AshLibVariantLookupPlan.SHIP_JSON_CLASS,
                    AshLibVariantLookupPlan.SHIP_JSON_METHOD,
                    AshLibVariantLookupPlan.SHIP_JSON_DESCRIPTOR,
                    false));
            caller.instructions.add(new InsnNode(Opcodes.ARETURN));
            caller.maxStack = 1;
            caller.maxLocals = 1;
            caller.accept(writer);
        }
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
