package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class DeploymentIconCachePlanTest {
    @BeforeEach
    @AfterEach
    void reset() {
        System.clearProperty(DeploymentIconCacheRuntime.ENABLED_PROPERTY);
        DeploymentIconCacheRuntime.beginSession();
    }

    @Test
    void exactGridKeepsTheOriginalAndInstrumentsEveryReviewedMutation() {
        byte[] transformed = DeploymentIconCachePlan.transform(signature(), fixture());
        assertNotNull(transformed);
        ClassNode node = read(transformed);
        assertNotNull(method(node, "preflight$original$getIconForMember"));
        MethodNode wrapper = method(node, DeploymentIconCachePlan.LOOKUP_METHOD);
        assertNotNull(wrapper);
        assertEquals(1, calls(wrapper, DeploymentIconCacheRuntime.class.getName()
                .replace('.', '/'), "lookup"));
        assertEquals(1, calls(wrapper, DeploymentIconCacheRuntime.class.getName()
                .replace('.', '/'), "record"));
        assertEquals(1, calls(wrapper, DeploymentIconCachePlan.ICON, "Öo0000"));
        assertEquals(0, calls(wrapper, DeploymentIconCachePlan.TARGET_CLASS,
                DeploymentIconCachePlan.MEMBERS_METHOD));
        assertEquals(0, calls(wrapper, DeploymentIconCachePlan.TARGET_CLASS,
                DeploymentIconCachePlan.LIST_METHOD));
        String runtime = DeploymentIconCacheRuntime.class.getName().replace('.', '/');
        assertEquals(1, calls(method(node, DeploymentIconCachePlan.CLEAR_METHOD),
                runtime, "cleared"));
        assertEquals(1, calls(method(node, DeploymentIconCachePlan.ADD_METHOD,
                DeploymentIconCachePlan.ADD_MEMBER_DESCRIPTOR), runtime, "added"));
        assertEquals(1, calls(method(node, DeploymentIconCachePlan.ADD_METHOD,
                DeploymentIconCachePlan.ADD_COMBAT_ENTRY_DESCRIPTOR), runtime, "added"));
        assertEquals(1, calls(method(node, DeploymentIconCachePlan.REMOVE_METHOD),
                runtime, "removed"));
    }

    @Test
    void wrongHashAndSecondRewriteFailClosed() {
        ClassSignature exact = signature();
        assertNull(DeploymentIconCachePlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(),
                exact.methods()), fixture()));
        byte[] once = DeploymentIconCachePlan.transform(exact, fixture());
        assertNotNull(once);
        assertNull(DeploymentIconCachePlan.transform(exact, once));
    }

    @Test
    void ownerMutationsKeepPositiveAnswersCurrentWithoutListValidation() {
        Object owner = new Object();
        Object member = new Object();
        Object icon = new Object();
        DeploymentIconCacheRuntime.installed();
        System.setProperty(DeploymentIconCacheRuntime.ENABLED_PROPERTY, "true");

        assertNull(DeploymentIconCacheRuntime.lookup(owner, member));
        DeploymentIconCacheRuntime.added(owner, member);
        assertNull(DeploymentIconCacheRuntime.lookup(owner, member));
        DeploymentIconCacheRuntime.record(owner, member, icon);
        assertSame(icon, DeploymentIconCacheRuntime.lookup(owner, member));
        DeploymentIconCacheRuntime.removed(owner, member);
        assertNull(DeploymentIconCacheRuntime.lookup(owner, member));
        DeploymentIconCacheRuntime.record(owner, member, icon);
        assertSame(icon, DeploymentIconCacheRuntime.lookup(owner, member));
        DeploymentIconCacheRuntime.cleared(owner);
        assertNull(DeploymentIconCacheRuntime.lookup(owner, member));

        assertEquals("instrumented-owner-mutations",
                DeploymentIconCacheRuntime.telemetry().get("validationStrategy"));
        assertEquals(0L, DeploymentIconCacheRuntime.telemetry().get("validatedReferences"));
        assertEquals(2L, DeploymentIconCacheRuntime.telemetry().get("hits"));
        assertTrue((Long) DeploymentIconCacheRuntime.telemetry().get("delegated") >= 4L);
    }

    @Test
    void duplicateAddsPreserveTheOriginalMethodsFirstMatchSemantics() {
        Object owner = new Object();
        Object member = new Object();
        Object first = new Object();
        DeploymentIconCacheRuntime.installed();
        System.setProperty(DeploymentIconCacheRuntime.ENABLED_PROPERTY, "true");

        DeploymentIconCacheRuntime.added(owner, member);
        DeploymentIconCacheRuntime.record(owner, member, first);
        DeploymentIconCacheRuntime.added(owner, member);
        assertNull(DeploymentIconCacheRuntime.lookup(owner, member),
                "an add must delegate once because an earlier duplicate may exist");
        DeploymentIconCacheRuntime.record(owner, member, first);
        assertSame(first, DeploymentIconCacheRuntime.lookup(owner, member));
    }

    @Test
    void gateOffNeverReturnsOrRecordsAnything() {
        DeploymentIconCacheRuntime.installed();
        List<Object> values = new ArrayList<>(List.of(new Object()));
        Object owner = new Object();
        Object member = values.get(0);
        DeploymentIconCacheRuntime.record(owner, member, new Object());
        assertFalse(DeploymentIconCacheRuntime.enabled());
        assertNull(DeploymentIconCacheRuntime.lookup(owner, member));
        assertEquals(0L, DeploymentIconCacheRuntime.telemetry().get("snapshots"));
    }

    @Test
    void targetBindsTheExactCoreArchiveAndAppLoader() {
        AdapterTarget target = AdapterTargetRegistry.deploymentIconCacheTarget();
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                "file:/game/Contents/Resources/Java/starfarer_obf.jar",
                "/game/contents/resources/java/starfarer_obf.jar",
                "STARSECTOR_CORE",
                target.sourceSha256(),
                "",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");
        assertTrue(target.match(signature(), source).exact());
    }

    private static ClassSignature signature() {
        return new ClassSignature(
                DeploymentIconCachePlan.TARGET_CLASS,
                DeploymentIconCachePlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(
                        new ClassSignature.Method(
                                DeploymentIconCachePlan.LOOKUP_METHOD,
                                DeploymentIconCachePlan.LOOKUP_DESCRIPTOR,
                                Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method(
                                DeploymentIconCachePlan.MEMBERS_METHOD,
                                DeploymentIconCachePlan.MEMBERS_DESCRIPTOR,
                                Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method(
                                DeploymentIconCachePlan.LIST_METHOD,
                                DeploymentIconCachePlan.LIST_DESCRIPTOR,
                                Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method(
                                DeploymentIconCachePlan.CLEAR_METHOD,
                                DeploymentIconCachePlan.CLEAR_DESCRIPTOR,
                                Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method(
                                DeploymentIconCachePlan.ADD_METHOD,
                                DeploymentIconCachePlan.ADD_MEMBER_DESCRIPTOR,
                                Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method(
                                DeploymentIconCachePlan.ADD_METHOD,
                                DeploymentIconCachePlan.ADD_COMBAT_ENTRY_DESCRIPTOR,
                                Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method(
                                DeploymentIconCachePlan.REMOVE_METHOD,
                                DeploymentIconCachePlan.REMOVE_DESCRIPTOR,
                                Opcodes.ACC_PUBLIC)));
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, DeploymentIconCachePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodNode lookup = new MethodNode(Opcodes.ACC_PUBLIC,
                DeploymentIconCachePlan.LOOKUP_METHOD,
                DeploymentIconCachePlan.LOOKUP_DESCRIPTOR, null, null);
        lookup.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        lookup.instructions.add(new InsnNode(Opcodes.ARETURN));
        lookup.accept(writer);
        MethodNode members = new MethodNode(Opcodes.ACC_PUBLIC,
                DeploymentIconCachePlan.MEMBERS_METHOD,
                DeploymentIconCachePlan.MEMBERS_DESCRIPTOR, null, null);
        members.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        members.instructions.add(new InsnNode(Opcodes.ARETURN));
        members.accept(writer);
        MethodNode list = new MethodNode(Opcodes.ACC_PUBLIC,
                DeploymentIconCachePlan.LIST_METHOD,
                DeploymentIconCachePlan.LIST_DESCRIPTOR, null, null);
        list.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        list.instructions.add(new InsnNode(Opcodes.ARETURN));
        list.accept(writer);
        voidMethod(writer, DeploymentIconCachePlan.CLEAR_METHOD,
                DeploymentIconCachePlan.CLEAR_DESCRIPTOR);
        voidMethod(writer, DeploymentIconCachePlan.ADD_METHOD,
                DeploymentIconCachePlan.ADD_MEMBER_DESCRIPTOR);
        voidMethod(writer, DeploymentIconCachePlan.ADD_METHOD,
                DeploymentIconCachePlan.ADD_COMBAT_ENTRY_DESCRIPTOR);
        voidMethod(writer, DeploymentIconCachePlan.REMOVE_METHOD,
                DeploymentIconCachePlan.REMOVE_DESCRIPTOR);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void voidMethod(ClassWriter writer, String name, String descriptor) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.accept(writer);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(value -> name.equals(value.name)).findFirst().orElse(null);
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(value -> name.equals(value.name) && descriptor.equals(value.desc))
                .findFirst().orElse(null);
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }
}
