package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

class RadarRenderPlanTest {
    private static final List<String> TYPES = List.of(
            "com/fs/starfarer/campaign/CampaignPlanet",
            "com/fs/starfarer/campaign/CustomCampaignEntity",
            "com/fs/starfarer/campaign/CampaignOrbitalStation",
            "com/fs/starfarer/campaign/JumpPoint",
            "com/fs/starfarer/campaign/fleet/CampaignFleet",
            "com/fs/starfarer/campaign/CampaignTerrain",
            "com/fs/starfarer/campaign/NascentGravityWell");

    @BeforeEach
    @AfterEach
    void reset() {
        System.clearProperty(EntityLookupRuntime.ENABLED_PROPERTY);
        EntityLookupRuntime.beginSession();
        RadarRenderRuntime.beginSession();
    }

    @Test
    void exactRendererRetainsVanillaBranchAndAddsOneStaticSet() throws Exception {
        byte[] fixture = fixture(true);
        byte[] transformed = RadarRenderPlan.transform(signature(fixture), fixture);
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        var field = owner.fields.stream()
                .filter(candidate -> "preflight$radarTypes".equals(candidate.name))
                .findFirst().orElseThrow();
        assertTrue((field.access & Opcodes.ACC_PRIVATE) != 0);
        assertTrue((field.access & Opcodes.ACC_STATIC) != 0);
        assertTrue((field.access & Opcodes.ACC_FINAL) != 0);
        assertTrue((field.access & Opcodes.ACC_SYNTHETIC) != 0);

        MethodNode render = method(owner, RadarRenderPlan.RENDER_METHOD);
        String runtime = RadarRenderRuntime.class.getName().replace('.', '/');
        assertEquals(1, calls(render, runtime, "enabled"));
        assertEquals(1, calls(render, runtime, "hit"));
        assertEquals(1, newInstances(render, "java/util/HashSet"),
                "gate-off must retain the reviewed vanilla allocation path");
        assertEquals(1, staticReads(render, owner.name, "preflight$radarTypes"));

        MethodNode initializer = method(owner, "<clinit>");
        assertEquals(1, newInstances(initializer, "java/util/HashSet"));
        assertEquals(7, calls(initializer, "java/util/Set", "add"));
    }

    @Test
    void wrongHashChangedTypeListAndSecondRewriteFailClosed() throws Exception {
        byte[] exact = fixture(true);
        ClassSignature signature = signature(exact);
        assertNull(RadarRenderPlan.transform(new ClassSignature(
                signature.internalName(), "0".repeat(64), signature.majorVersion(),
                signature.access(), signature.methods()), exact));
        assertNull(RadarRenderPlan.transform(signature(fixture(false)), fixture(false)));
        byte[] once = RadarRenderPlan.transform(signature, exact);
        assertNotNull(once);
        assertNull(RadarRenderPlan.transform(signature, once));
    }

    @Test
    void runtimeRequiresTheExistingExactGameplayGate() {
        RadarRenderRuntime.installed();
        System.setProperty(EntityLookupRuntime.ENABLED_PROPERTY, "true");
        assertFalse(RadarRenderRuntime.enabled());
        EntityLookupRuntime.locationInstalled();
        EntityLookupRuntime.repositoryInstalled();
        EntityLookupRuntime.idMutationInstalled();
        assertTrue(RadarRenderRuntime.enabled());
        RadarRenderRuntime.hit();
        assertEquals(1L, RadarRenderRuntime.telemetry().get("cachedFrames"));
    }

    @Test
    void targetPinsReviewedCoreArchiveAndAppLoader() throws Exception {
        byte[] fixture = fixture(true);
        AdapterTarget target = AdapterTargetRegistry.radarRenderTarget();
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                "file:/game/Contents/Resources/Java/starfarer_obf.jar",
                "/game/contents/resources/java/starfarer_obf.jar",
                "STARSECTOR_CORE", target.sourceSha256(), "",
                "jdk/internal/loader/ClassLoaders$AppClassLoader", "app");
        assertTrue(target.match(signature(fixture), source).exact());
        assertTrue(AdapterTransformationRegistry.hasPlan(target.planId()));
    }

    private static ClassSignature signature(byte[] fixture) throws Exception {
        ClassSignature parsed = ClassSignature.parse(fixture);
        return new ClassSignature(parsed.internalName(), RadarRenderPlan.ORIGINAL_SHA256,
                parsed.majorVersion(), parsed.access(), parsed.methods());
    }

    private static byte[] fixture(boolean reviewed) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RadarRenderPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodNode initializer = new MethodNode(
                Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        initializer.instructions.add(new InsnNode(Opcodes.RETURN));
        initializer.accept(writer);

        MethodNode render = new MethodNode(
                Opcodes.ACC_PUBLIC, RadarRenderPlan.RENDER_METHOD,
                RadarRenderPlan.RENDER_DESCRIPTOR, null, null);
        render.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashSet"));
        render.instructions.add(new InsnNode(Opcodes.DUP));
        render.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "()V", false));
        render.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        for (int i = 0; i < TYPES.size(); i++) {
            render.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            String type = !reviewed && i == TYPES.size() - 1
                    ? "java/lang/String" : TYPES.get(i);
            render.instructions.add(new LdcInsnNode(Type.getObjectType(type)));
            render.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, "java/util/Set", "add",
                    "(Ljava/lang/Object;)Z", true));
            render.instructions.add(new InsnNode(Opcodes.POP));
        }
        render.instructions.add(new InsnNode(Opcodes.RETURN));
        render.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(candidate -> name.equals(candidate.name))
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

    private static int newInstances(MethodNode method, String type) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode allocation
                    && allocation.getOpcode() == Opcodes.NEW && type.equals(allocation.desc)) count++;
        }
        return count;
    }

    private static int staticReads(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && owner.equals(field.owner) && name.equals(field.name)) count++;
        }
        return count;
    }
}
