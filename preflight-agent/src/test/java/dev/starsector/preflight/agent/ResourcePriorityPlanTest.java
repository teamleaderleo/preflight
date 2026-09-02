package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class ResourcePriorityPlanTest {
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/ResourcePriorityRuntime";

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void resetRuntimeState() {
        StartupPhaseRuntime.beginSession(null);
        StartupPhaseRuntime.enableMergedReadProbe(false);
        LoadJsonMemoRuntime.enable(false);
        LoadJsonMemoRuntime.reset();
        FrameTimeRuntime.reset();
        RuntimeSemanticState.reset();
        System.clearProperty(TexturePreparedPrefetchPlan.WINDOWS_RESOURCE_ORDER_PROPERTY);
    }

    @Test
    void rewritesOnlyTheReviewedRemoveThenPrependShape() throws Exception {
        byte[] original = fixture(true);
        byte[] rewritten = ResourcePriorityPlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(1, runtimeCalls(rewritten));
        assertNull(ResourcePriorityPlan.transform(ClassSignature.parse(rewritten), rewritten));
    }

    @Test
    void refusesARemoveAllWithoutTheFollowingStablePrepend() throws Exception {
        byte[] drifted = fixture(false);
        assertNull(ResourcePriorityPlan.transform(ClassSignature.parse(drifted), drifted));
    }

    @Test
    void linuxTargetPinsTheReviewedFlatDistributionAsAPlatformAlternative() {
        AdapterTarget mac = AdapterTargetRegistry.resourcePriorityTarget();
        AdapterTarget linux = AdapterTargetRegistry.linuxResourcePriorityTarget();

        assertEquals(ResourcePriorityRuntime.PLAN_ID, linux.planId());
        assertEquals("133e67fc16877b7f7550aa15540b1d8a998373c59ca70f05b99e269be308053f",
                linux.sha256());
        assertEquals("starfarer_obf.jar", linux.sourceSuffix());
        assertEquals("3d41d31d4840158491426f0570f42d71c176d9bc9cc84605a284e4c76c8b91b0",
                linux.sourceSha256());
        assertEquals(mac.alternativeGroup(), linux.alternativeGroup());
    }

    @Test
    void windowsTargetPinsTheReviewedFlatDistributionAsAPlatformAlternative() {
        AdapterTarget mac = AdapterTargetRegistry.resourcePriorityTarget();
        AdapterTarget windows = AdapterTargetRegistry.windowsResourcePriorityTarget();

        assertEquals(ResourcePriorityRuntime.PLAN_ID, windows.planId());
        assertEquals("91234c03bb3938180f5a4a0c552eaf2af46df57774530890441355c18e86b6de",
                windows.sha256());
        assertEquals("starfarer_obf.jar", windows.sourceSuffix());
        assertEquals("5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8",
                windows.sourceSha256());
        assertEquals(mac.alternativeGroup(), windows.alternativeGroup());
    }

    @Test
    void delaysTheExactWindowsPreparedWorkerUntilAfterStockPriorityOrder() throws Exception {
        System.setProperty(TexturePreparedPrefetchPlan.WINDOWS_RESOURCE_ORDER_PROPERTY, "true");
        byte[] original = fixture(true);
        ClassSignature parsed = ClassSignature.parse(original);
        ClassSignature windows = new ClassSignature(
                parsed.internalName(),
                FrameTimeStartupCompletionPlan.WINDOWS_ORIGINAL_SHA256,
                parsed.majorVersion(),
                parsed.access(),
                parsed.methods());

        byte[] rewritten = TexturePreparedPriorityPlan.transform(windows, original);

        assertNotNull(rewritten);
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(rewritten).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode init = owner.methods.stream()
                .filter(method -> ResourcePriorityPlan.INIT_METHOD.equals(method.name)
                        && ResourcePriorityPlan.INIT_DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
        MethodInsnNode addAll = call(init, "java/util/List", "addAll");
        MethodInsnNode remember = call(init,
                "dev/starsector/preflight/agent/TexturePreparedPixelRuntime",
                "rememberPreparedPrefetchOrder");
        MethodInsnNode worker = call(init, TexturePreparedPrefetchPlan.TARGET_CLASS, "o00000");
        assertAfter(addAll, remember);
        assertAfter(remember, worker);
    }

    @Test
    void lightweightCompletionMarkerBacksUpAnUnavailableDetailedPhaseShape() throws Exception {
        LoadJsonMemoRuntime.enable(true);
        StartupPhaseRuntime.beginSession(temporaryDirectory.resolve("phases.json"));
        StartupPhaseRuntime.enableMergedReadProbe(true);
        byte[] original = fixture(true);
        ClassSignature parsed = ClassSignature.parse(original);
        ClassSignature windows = new ClassSignature(
                parsed.internalName(),
                FrameTimeStartupCompletionPlan.WINDOWS_ORIGINAL_SHA256,
                parsed.majorVersion(),
                parsed.access(),
                parsed.methods());

        byte[] rewritten = AdapterTransformationRegistry.transform(
                AdapterTargetRegistry.windowsResourcePriorityTarget(), windows, original);

        assertNotNull(rewritten);
        assertEquals(1, markerCalls(rewritten, "LoadJsonMemoRuntime", "markProfileStable"));
        assertEquals(1, markerCalls(rewritten, "FrameTimeRuntime", "markStartupComplete"));
        assertFalse((Boolean) StartupPhaseRuntime.telemetry().get("installed"),
                "the lightweight fallback must not claim that detailed phases were installed");
    }

    private static byte[] fixture(boolean prepend) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, ResourcePriorityPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "resources", "Ljava/util/List;", null, null).visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, ResourcePriorityPlan.INIT_METHOD,
                ResourcePriorityPlan.INIT_DESCRIPTOR, null, null);
        init.visitCode();
        init.visitMethodInsn(Opcodes.INVOKESTATIC, TexturePreparedPrefetchPlan.TARGET_CLASS,
                "o00000", "()V", false);
        init.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ASTORE, 2);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitFieldInsn(Opcodes.GETFIELD, ResourcePriorityPlan.TARGET_CLASS,
                "resources", "Ljava/util/List;");
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "removeAll",
                "(Ljava/util/Collection;)Z", true);
        init.visitInsn(Opcodes.POP);
        if (prepend) {
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitFieldInsn(Opcodes.GETFIELD, ResourcePriorityPlan.TARGET_CLASS,
                    "resources", "Ljava/util/List;");
            init.visitInsn(Opcodes.ICONST_0);
            init.visitVarInsn(Opcodes.ALOAD, 2);
            init.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "addAll",
                    "(ILjava/util/Collection;)Z", true);
            init.visitInsn(Opcodes.POP);
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int runtimeCalls(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode init = owner.methods.stream()
                .filter(method -> ResourcePriorityPlan.INIT_METHOD.equals(method.name)
                        && ResourcePriorityPlan.INIT_DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
        int calls = 0;
        for (AbstractInsnNode instruction : init.instructions) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)
                    && "removeAll".equals(call.name)) {
                calls++;
            }
        }
        return calls;
    }

    private static int markerCalls(byte[] bytes, String runtime, String methodName) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        int calls = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.endsWith("/" + runtime)
                        && methodName.equals(call.name)) {
                    calls++;
                }
            }
        }
        return calls;
    }

    private static MethodInsnNode call(MethodNode method, String owner, String name) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode candidate
                    && owner.equals(candidate.owner) && name.equals(candidate.name)) {
                return candidate;
            }
        }
        throw new AssertionError("missing call " + owner + "." + name);
    }

    private static void assertAfter(AbstractInsnNode first, AbstractInsnNode second) {
        for (AbstractInsnNode cursor = first; cursor != null; cursor = cursor.getNext()) {
            if (cursor == second) return;
        }
        throw new AssertionError("expected second instruction after first");
    }
}
