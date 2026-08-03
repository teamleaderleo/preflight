package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void transformedRendererExecutesAndScopesReuseToOneRender() throws Exception {
        byte[] transformed = GraphicsLibInsigniaManagerCachePlan.transform(signature(), fixture());
        assertNotNull(transformed);
        Class<?> pluginClass = new FixtureLoader(getClass().getClassLoader())
                .define(GraphicsLibInsigniaManagerCachePlan.TARGET_CLASS.replace('/', '.'), transformed);
        Object plugin = pluginClass.getConstructor().newInstance();

        AtomicInteger accessorCalls = new AtomicInteger();
        CombatFleetManagerAPI manager = new CombatFleetManagerAPI() { };
        CombatEngineAPI engine = owner -> {
            accessorCalls.incrementAndGet();
            assertEquals(0, owner);
            return manager;
        };
        Field engineField = pluginClass.getDeclaredField("engine");
        engineField.setAccessible(true);
        engineField.set(plugin, engine);

        GraphicsLibInsigniaManagerCacheRuntime.configure(true);
        var render = pluginClass.getMethod(
                GraphicsLibInsigniaManagerCachePlan.RENDER_METHOD, ViewportAPI.class);
        render.invoke(plugin, new ViewportAPI() { });
        assertEquals(1, accessorCalls.get(), "two ships with one owner share one real lookup");
        assertTelemetry(1L, 1L);

        render.invoke(plugin, new ViewportAPI() { });
        assertEquals(2, accessorCalls.get(), "the next render must revalidate fleet-manager topology");
        assertTelemetry(2L, 2L);

        Field cacheField = pluginClass.getDeclaredField("preflight$managersThisRender");
        cacheField.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) cacheField.get(plugin);
        assertEquals(1, cache.size());
        assertSame(manager, cache.get(0));
    }

    @Test
    void transformedRendererReusesANullManagerResult() throws Exception {
        byte[] transformed = GraphicsLibInsigniaManagerCachePlan.transform(signature(), fixture());
        assertNotNull(transformed);
        Class<?> pluginClass = new FixtureLoader(getClass().getClassLoader())
                .define(GraphicsLibInsigniaManagerCachePlan.TARGET_CLASS.replace('/', '.'), transformed);
        Object plugin = pluginClass.getConstructor().newInstance();

        AtomicInteger accessorCalls = new AtomicInteger();
        CombatEngineAPI engine = owner -> {
            accessorCalls.incrementAndGet();
            return null;
        };
        Field engineField = pluginClass.getDeclaredField("engine");
        engineField.setAccessible(true);
        engineField.set(plugin, engine);

        GraphicsLibInsigniaManagerCacheRuntime.configure(true);
        pluginClass.getMethod(GraphicsLibInsigniaManagerCachePlan.RENDER_METHOD, ViewportAPI.class)
                .invoke(plugin, new ViewportAPI() { });
        assertEquals(1, accessorCalls.get(), "containsKey must distinguish cached null from no entry");
        assertTelemetry(1L, 1L);

        Field cacheField = pluginClass.getDeclaredField("preflight$managersThisRender");
        cacheField.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) cacheField.get(plugin);
        assertTrue(cache.containsKey(0));
        assertNull(cache.get(0));
    }

    private static void assertTelemetry(long expectedHits, long expectedMisses) {
        Map<String, Object> telemetry = GraphicsLibInsigniaManagerCacheRuntime.telemetry();
        assertEquals(expectedHits, telemetry.get("hits"));
        assertEquals(expectedMisses, telemetry.get("misses"));
        assertEquals(expectedHits + expectedMisses, telemetry.get("requests"));
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
        render.visitInsn(Opcodes.ICONST_0);
        render.visitVarInsn(Opcodes.ISTORE, 2);
        org.objectweb.asm.Label loop = new org.objectweb.asm.Label();
        render.visitLabel(loop);
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
        render.visitIincInsn(2, 1);
        render.visitVarInsn(Opcodes.ILOAD, 2);
        render.visitInsn(Opcodes.ICONST_2);
        render.visitJumpInsn(Opcodes.IF_ICMPLT, loop);
        render.visitInsn(Opcodes.RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class FixtureLoader extends ClassLoader {
        private FixtureLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
