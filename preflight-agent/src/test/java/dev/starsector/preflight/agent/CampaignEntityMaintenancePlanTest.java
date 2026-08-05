package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class CampaignEntityMaintenancePlanTest {
    @BeforeEach
    void enable() {
        CampaignEntityMaintenanceRuntime.beginSession();
    }

    @AfterEach
    void reset() {
        System.clearProperty(CampaignEntityMaintenanceRuntime.DISABLED_PROPERTY);
        CampaignEntityMaintenanceRuntime.beginSession();
    }

    @Test
    void entityWrapperSkipsEmptyListsAndRunsTheUnchangedNonEmptyPath() throws Exception {
        byte[] original = entityFixture();
        byte[] transformed = CampaignEntityMaintenancePlan.transform(
                exact(original, CampaignEntityMaintenancePlan.ENTITY_SHA256), original);
        assertNotNull(transformed);
        assertNotNull(method(transformed, CampaignEntityMaintenancePlan.SCRIPT_METHOD));
        assertNotNull(method(transformed, "preflight$original$runScripts"));
        assertNull(CampaignEntityMaintenancePlan.transform(
                ClassSignature.parse(transformed), transformed));

        ByteArrayLoader loader = new ByteArrayLoader(Map.of(
                CampaignEntityMaintenancePlan.ENTITY_CLASS.replace('/', '.'), transformed,
                "com.fs.starfarer.api.EveryFrameScript", scriptFixture()));
        Class<?> scriptType = loader.loadClass("com.fs.starfarer.api.EveryFrameScript");
        Class<?> entityType = loader.loadClass(
                CampaignEntityMaintenancePlan.ENTITY_CLASS.replace('/', '.'));
        Object entity = entityType.getConstructor().newInstance();
        var advance = entityType.getDeclaredMethod(
                CampaignEntityMaintenancePlan.SCRIPT_METHOD, float.class);
        advance.setAccessible(true);

        advance.invoke(entity, 1f);
        AtomicInteger calls = new AtomicInteger();
        Object script = Proxy.newProxyInstance(loader, new Class<?>[] {scriptType},
                (proxy, method, arguments) -> {
                    if ("advance".equals(method.getName())) calls.incrementAndGet();
                    return null;
                });
        var scripts = entityType.getDeclaredField("scripts");
        scripts.setAccessible(true);
        @SuppressWarnings("unchecked")
        ArrayList<Object> values = (ArrayList<Object>) scripts.get(entity);
        values.add(script);
        advance.invoke(entity, 1f);

        assertEquals(1, calls.get());
        assertEquals(1L, CampaignEntityMaintenanceRuntime.telemetry().get("emptyScriptLists"));
        assertEquals(1L, CampaignEntityMaintenanceRuntime.telemetry().get("nonEmptyScriptLists"));
    }

    @Test
    void fleetViewReusesFirstSnapshotAndDisabledRuntimeDeclines() throws Exception {
        byte[] original = fleetViewFixture();
        byte[] transformed = CampaignEntityMaintenancePlan.transform(
                exact(original, CampaignEntityMaintenancePlan.FLEET_VIEW_SHA256), original);
        assertNotNull(transformed);
        assertEquals(1, calls(method(transformed, CampaignEntityMaintenancePlan.ADVANCE_METHOD),
                "com/fs/starfarer/campaign/fleet/CampaignFleet", "getSortedMembers"));

        System.setProperty(CampaignEntityMaintenanceRuntime.DISABLED_PROPERTY, "true");
        CampaignEntityMaintenanceRuntime.beginSession();
        assertNull(CampaignEntityMaintenancePlan.transform(
                exact(original, CampaignEntityMaintenancePlan.FLEET_VIEW_SHA256), original));
    }

    private static byte[] entityFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                CampaignEntityMaintenancePlan.ENTITY_CLASS, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "scripts", "Ljava/util/List;", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        constructor.visitInsn(Opcodes.DUP);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/util/ArrayList", "<init>", "()V", false);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, CampaignEntityMaintenancePlan.ENTITY_CLASS,
                "scripts", "Ljava/util/List;");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(3, 1);
        constructor.visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE,
                CampaignEntityMaintenancePlan.SCRIPT_METHOD,
                CampaignEntityMaintenancePlan.SCRIPT_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        method.visitInsn(Opcodes.DUP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, CampaignEntityMaintenancePlan.ENTITY_CLASS,
                "scripts", "Ljava/util/List;");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
                "(Ljava/util/Collection;)V", false);
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, CampaignEntityMaintenancePlan.ENTITY_CLASS,
                "scripts", "Ljava/util/List;");
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, CampaignEntityMaintenancePlan.ENTITY_CLASS,
                "scripts", "Ljava/util/List;");
        method.visitInsn(Opcodes.ICONST_0);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        method.visitTypeInsn(Opcodes.CHECKCAST, "com/fs/starfarer/api/EveryFrameScript");
        method.visitVarInsn(Opcodes.FLOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/EveryFrameScript", "advance", "(F)V", true);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(4, 2);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] scriptFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "com/fs/starfarer/api/EveryFrameScript", null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "advance", "(F)V", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] fleetViewFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                CampaignEntityMaintenancePlan.FLEET_VIEW_CLASS, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "fleet",
                "Lcom/fs/starfarer/campaign/fleet/CampaignFleet;", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CampaignEntityMaintenancePlan.ADVANCE_METHOD,
                CampaignEntityMaintenancePlan.ADVANCE_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, CampaignEntityMaintenancePlan.FLEET_VIEW_CLASS,
                "fleet", "Lcom/fs/starfarer/campaign/fleet/CampaignFleet;");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/fleet/CampaignFleet", "getSortedMembers",
                "()Ljava/util/List;", false);
        method.visitVarInsn(Opcodes.ASTORE, 3);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, CampaignEntityMaintenancePlan.FLEET_VIEW_CLASS,
                "fleet", "Lcom/fs/starfarer/campaign/fleet/CampaignFleet;");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/fleet/CampaignFleet", "getSortedMembers",
                "()Ljava/util/List;", false);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "java/util/List", "size", "()I", true);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 4);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exact(byte[] bytes, String hash) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), hash, parsed.majorVersion(),
                parsed.access(), parsed.methods());
    }

    private static MethodNode method(byte[] bytes, String name) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream().filter(candidate -> name.equals(candidate.name))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private ByteArrayLoader(Map<String, byte[]> classes) {
            super(CampaignEntityMaintenancePlanTest.class.getClassLoader());
            this.classes = Map.copyOf(classes);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && classes.containsKey(name)) {
                byte[] bytes = classes.get(name);
                loaded = defineClass(name, bytes, 0, bytes.length);
            }
            if (loaded == null) loaded = super.loadClass(name, false);
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }
}
