package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class GraphicsLibInsigniaManagerCachePlanTest {
    @AfterEach
    void reset() {
        GraphicsLibInsigniaManagerCacheRuntime.beginSession();
    }

    @Test
    void exactRendererCachesTheSingleManagerAccessorWithoutReplacingRenderMath() {
        byte[] original = fixture();
        byte[] transformed = GraphicsLibInsigniaManagerCachePlan.transform(signature(), original);
        assertNotNull(transformed);

        ClassNode node = read(transformed);
        assertTrue(node.fields.stream()
                .anyMatch(field -> "preflight$managersThisRender".equals(field.name)));
        MethodNode render = method(node, GraphicsLibInsigniaManagerCachePlan.RENDER_METHOD);
        MethodNode helper = method(node, "preflight$getFleetManager");
        assertNotNull(render);
        assertNotNull(helper);
        assertEquals(1, calls(render, node.name, "preflight$getFleetManager"));
        assertEquals(0, calls(
                render,
                "com/fs/starfarer/api/combat/CombatEngineAPI",
                "getFleetManager"));
        assertEquals(1, calls(
                helper,
                "com/fs/starfarer/api/combat/CombatEngineAPI",
                "getFleetManager"));
        assertTrue(render.instructions.iterator().hasNext(), "the original render body remains present");
        assertTrue(hasFieldAccess(render, "preflight$managersThisRender"));
    }

    @Test
    void wrongHashShapeAndSecondRewriteFailClosed() {
        byte[] original = fixture();
        ClassSignature exact = signature();
        assertNull(GraphicsLibInsigniaManagerCachePlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), 61, exact.access(), exact.methods()), original));

        byte[] once = GraphicsLibInsigniaManagerCachePlan.transform(exact, original);
        assertNotNull(once);
        assertNull(GraphicsLibInsigniaManagerCachePlan.transform(exact, once));
    }

    @Test
    void targetBindsExactGraphicsArchiveAndPortableModLoader() {
        AdapterTarget target = AdapterTargetRegistry.graphicsLibInsigniaManagerCacheTarget();
        AdapterSourceIdentity exactSource = new AdapterSourceIdentity(
                "file:/game/mods/GraphicsLib/jars/Graphics.jar",
                "C:/game/mods/GraphicsLib/jars/Graphics.jar",
                "MOD",
                target.sourceSha256(),
                "",
                "java/net/URLClassLoader",
                "");
        assertTrue(target.match(signature(), exactSource).exact());
        assertTrue(target.hasLiveSourceBinding());

        AdapterSourceIdentity changedArchive = new AdapterSourceIdentity(
                exactSource.codeSource(), exactSource.normalizedSource(), "MOD", "f".repeat(64), "",
                exactSource.loaderClass(), "");
        assertFalse(target.match(signature(), changedArchive).exact());
    }

    @Test
    void telemetrySeparatesReusedAndRealManagerRequests() {
        GraphicsLibInsigniaManagerCacheRuntime.configure(true);
        GraphicsLibInsigniaManagerCacheRuntime.miss();
        GraphicsLibInsigniaManagerCacheRuntime.hit();
        GraphicsLibInsigniaManagerCacheRuntime.hit();

        assertEquals(2L, GraphicsLibInsigniaManagerCacheRuntime.telemetry().get("hits"));
        assertEquals(1L, GraphicsLibInsigniaManagerCacheRuntime.telemetry().get("misses"));
        assertEquals(3L, GraphicsLibInsigniaManagerCacheRuntime.telemetry().get("requests"));
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasFieldAccess(MethodNode method, String name) {
        for (var instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && name.equals(field.name)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(method -> name.equals(method.name)).findFirst().orElse(null);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static ClassSignature signature() {
        return new ClassSignature(
                GraphicsLibInsigniaManagerCachePlan.TARGET_CLASS,
                GraphicsLibInsigniaManagerCachePlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(
                        GraphicsLibInsigniaManagerCachePlan.RENDER_METHOD,
                        GraphicsLibInsigniaManagerCachePlan.RENDER_DESCRIPTOR,
                        Opcodes.ACC_PUBLIC)));
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                GraphicsLibInsigniaManagerCachePlan.TARGET_CLASS,
                null,
                "java/lang/Object",
                null);
        writer.visitField(
                Opcodes.ACC_PRIVATE,
                "engine",
                "Lcom/fs/starfarer/api/combat/CombatEngineAPI;",
                null,
                null).visitEnd();
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        var render = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                GraphicsLibInsigniaManagerCachePlan.RENDER_METHOD,
                GraphicsLibInsigniaManagerCachePlan.RENDER_DESCRIPTOR,
                null,
                null);
        render.visitCode();
        render.visitVarInsn(Opcodes.ALOAD, 0);
        render.visitFieldInsn(
                Opcodes.GETFIELD,
                GraphicsLibInsigniaManagerCachePlan.TARGET_CLASS,
                "engine",
                "Lcom/fs/starfarer/api/combat/CombatEngineAPI;");
        render.visitInsn(Opcodes.ICONST_0);
        render.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/combat/CombatEngineAPI",
                "getFleetManager",
                "(I)Lcom/fs/starfarer/api/combat/CombatFleetManagerAPI;",
                true);
        render.visitInsn(Opcodes.POP);
        render.visitInsn(Opcodes.RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
