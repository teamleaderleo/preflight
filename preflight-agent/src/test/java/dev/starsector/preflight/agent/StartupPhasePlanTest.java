package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class StartupPhasePlanTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";

    @Test
    void marksEveryHiddenTailBoundaryAndEachPluginCallback() throws Exception {
        byte[] original = fixture(true);
        byte[] rewritten = StartupPhasePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        MethodNode init = init(rewritten);
        assertEquals(List.of(
                        "resource-init-enter",
                        "loading-screen-ready",
                        "resource-manifest-start",
                        "resource-manifest-complete",
                        "script-discovery-start",
                        "script-discovery-core-complete",
                        "script-plugin-registration-complete",
                        "script-compile-complete",
                        "script-store-prime-complete",
                        "pre-progress-data-complete",
                        "spec-store-start",
                        "spec-store-complete",
                        "post-spec-progress-start",
                        "post-spec-progress-complete",
                        "ship-weapon-sprite-queue-start",
                        "ship-weapon-sprite-queue-complete",
                        "post-queue-progress-start",
                        "post-queue-progress-complete",
                        "resource-ordering-start",
                        "resource-ordering-complete",
                        "resource-executor-start",
                        "resource-executor-complete",
                        "resource-batches-start",
                        "progress-100",
                        "audio-workers-complete",
                        "graphics-finalize-complete",
                        "script-store-start",
                        "script-store-complete",
                        "mod-callbacks-start",
                        "mod-callbacks-complete",
                        "resource-init-complete"),
                phaseNames(init));
        assertEquals(1, runtimeCalls(progressMethod(rewritten), "progress"));
        assertEquals(1, runtimeCalls(init, "pluginStart"));
        assertEquals(1, runtimeCalls(init, "pluginEnd"));
        assertTrue(hasDupImmediatelyBeforePluginStart(init),
                "the callback receiver must remain on the operand stack for the shipped invocation");
    }

    @Test
    void refusesAClassWhoseReviewedTailShapeIsIncomplete() throws Exception {
        byte[] incomplete = fixture(false);
        assertNull(StartupPhasePlan.transform(ClassSignature.parse(incomplete), incomplete));
    }

    @Test
    void refusesToWeaveTwice() throws Exception {
        byte[] original = fixture(true);
        byte[] once = StartupPhasePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(StartupPhasePlan.transform(ClassSignature.parse(once), once));
    }

    @Test
    void registersExactLinuxAndWindowsPlatformAlternatives() {
        AdapterTarget mac = AdapterTargetRegistry.startupPhaseTarget();
        AdapterTarget linux = AdapterTargetRegistry.linuxStartupPhaseTarget();
        AdapterTarget windows = AdapterTargetRegistry.windowsStartupPhaseTarget();

        assertEquals(FrameTimeStartupCompletionPlan.LINUX_ORIGINAL_SHA256, linux.sha256());
        assertEquals("starfarer_obf.jar", linux.sourceSuffix());
        assertEquals("3d41d31d4840158491426f0570f42d71c176d9bc9cc84605a284e4c76c8b91b0",
                linux.sourceSha256());
        assertEquals(FrameTimeStartupCompletionPlan.WINDOWS_ORIGINAL_SHA256, windows.sha256());
        assertEquals("starfarer_obf.jar", windows.sourceSuffix());
        assertEquals("5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8",
                windows.sourceSha256());
        assertEquals(mac.alternativeGroup(), linux.alternativeGroup());
        assertEquals(mac.alternativeGroup(), windows.alternativeGroup());
    }

    @Test
    void registersExactPlatformAlternativesForDetailedSpecDecomposition() {
        assertPlatformAlternatives(
                AdapterTargetRegistry.specStorePhaseTarget(),
                AdapterTargetRegistry.linuxSpecStorePhaseTarget(),
                AdapterTargetRegistry.windowsSpecStorePhaseTarget(),
                "c24e0891883158c29767bd1d94cb41f4ce281418669d80b39472745626e23172",
                "011125fae8e21c0c1618d50258e9cf4b2292f0179093b3659ddc4f9a2555a5d8");
        assertPlatformAlternatives(
                AdapterTargetRegistry.weaponLoaderPhaseTarget(),
                AdapterTargetRegistry.linuxWeaponLoaderPhaseTarget(),
                AdapterTargetRegistry.windowsWeaponLoaderPhaseTarget(),
                "d551ae2441d94c338cc4000bff809a5bd0f8d0783dfe2d9147831d289f91644e",
                "fb7a0efe7ecd7e9b56b31832d89288ac8909da68fc49bbea7b721a4bca2e05bd");
        assertPlatformAlternatives(
                AdapterTargetRegistry.shipHullLoaderPhaseTarget(),
                AdapterTargetRegistry.linuxShipHullLoaderPhaseTarget(),
                AdapterTargetRegistry.windowsShipHullLoaderPhaseTarget(),
                "1132ea9ddf52b2d6293f9ac8379fbb7dee3181ca5652a87bcf6f64a655fc5c00",
                "93a78a8b95c8f9abf0cbcc5523efb706efe0c5f02cf6f3956a3a7dae78f91f43");
        assertPlatformAlternatives(
                AdapterTargetRegistry.rulesLoaderPhaseTarget(),
                AdapterTargetRegistry.linuxRulesLoaderPhaseTarget(),
                AdapterTargetRegistry.windowsRulesLoaderPhaseTarget(),
                "7865fa80d98032c50346f800daecdd2d0dd6935a67e0ab58159410aa7c7c2842",
                "72f0925d83ff48bfa2c4b8d2f691b10935d4567dc6ab1e12392a2ee388539df9");
        assertPlatformAlternatives(
                AdapterTargetRegistry.ruleExpressionPhaseTarget(),
                AdapterTargetRegistry.linuxRuleExpressionPhaseTarget(),
                AdapterTargetRegistry.windowsRuleExpressionPhaseTarget(),
                "894b652ad366387a6fb15dd066fca922c70411b502496a079cec2fd065a57760",
                "2161e729532ae56c5e3eb6738584f28742d95d272f7d87172fc4fffe5cbeeb13");

        assertEquals(WeaponLoaderPhasePlan.LINUX_LOAD_ALL_METHOD,
                AdapterTargetRegistry.windowsWeaponLoaderPhaseTarget()
                        .requiredMethods().get(0).name());
        assertEquals(ShipHullLoaderPhasePlan.LINUX_LOAD_ONE_METHOD,
                AdapterTargetRegistry.linuxShipHullLoaderPhaseTarget()
                        .requiredMethods().get(1).name());
        assertEquals(RulesLoaderPhasePlan.WINDOWS_LOAD_METHOD,
                AdapterTargetRegistry.windowsRulesLoaderPhaseTarget()
                        .requiredMethods().get(0).name());
        assertEquals(RuleExpressionPhasePlan.WINDOWS_TARGET_CLASS,
                AdapterTargetRegistry.windowsRuleExpressionPhaseTarget().internalClassName());
    }

    private static void assertPlatformAlternatives(
            AdapterTarget mac,
            AdapterTarget linux,
            AdapterTarget windows,
            String linuxClassSha256,
            String windowsClassSha256) {
        assertEquals(linuxClassSha256, linux.sha256());
        assertEquals(windowsClassSha256, windows.sha256());
        assertEquals("starfarer_obf.jar", linux.sourceSuffix());
        assertEquals("starfarer_obf.jar", windows.sourceSuffix());
        assertEquals("3d41d31d4840158491426f0570f42d71c176d9bc9cc84605a284e4c76c8b91b0",
                linux.sourceSha256());
        assertEquals("5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8",
                windows.sourceSha256());
        assertEquals(mac.alternativeGroup(), linux.alternativeGroup());
        assertEquals(mac.alternativeGroup(), windows.alternativeGroup());
    }

    private static byte[] fixture(boolean includePluginCallback) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, StartupPhasePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC, StartupPhasePlan.INIT_METHOD, StartupPhasePlan.INIT_DESCRIPTOR,
                null, new String[] {"java/lang/Exception"});
        method.visitCode();

        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, StartupPhasePlan.TARGET_CLASS,
                "renderBg", "()V", false);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/fs/starfarer/settings/StarfarerSettings",
                "o00000", "(Lcom/fs/starfarer/loading/ResourceLoaderState;)V", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "com/fs/starfarer/loading/scripts/ScriptStore", "ô00000", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "com/fs/starfarer/loading/scripts/ScriptStore", "int", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "com/fs/starfarer/loading/scripts/ScriptStore", "ö00000", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "com/fs/starfarer/title/C/A/Object", "o00000",
                "()Lcom/fs/starfarer/title/C/A/A;", false);
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.FCONST_0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, StartupPhasePlan.TARGET_CLASS,
                "renderProgress", "(F)V", false);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "com/fs/starfarer/loading/SpecStore", "ÓO0000",
                "(Lcom/fs/starfarer/loading/ResourceLoaderState;)V", false);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.FCONST_1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, StartupPhasePlan.TARGET_CLASS,
                "renderProgress", "(F)V", false);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, StartupPhasePlan.TARGET_CLASS,
                "queueShipAndWeaponSprites", "()V", false);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.FCONST_1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, StartupPhasePlan.TARGET_CLASS,
                "renderProgress", "(F)V", false);
        method.visitInsn(Opcodes.ICONST_2);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/concurrent/Executors",
                "newFixedThreadPool", "(I)Ljava/util/concurrent/ExecutorService;", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List",
                "iterator", "()Ljava/util/Iterator;", true);
        method.visitInsn(Opcodes.POP);

        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/concurrent/ExecutorService",
                "shutdown", "()V", true);
        Label retry = new Label();
        method.visitLabel(retry);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.LCONST_0);
        method.visitFieldInsn(Opcodes.GETSTATIC, "java/util/concurrent/TimeUnit", "SECONDS",
                "Ljava/util/concurrent/TimeUnit;");
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/concurrent/ExecutorService",
                "awaitTermination", "(JLjava/util/concurrent/TimeUnit;)Z", true);
        method.visitJumpInsn(Opcodes.IFEQ, retry);

        method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/fs/graphics/L", "new", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "com/fs/starfarer/loading/scripts/ScriptStore", "Ô00000", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/fs/starfarer/launcher/ModManager",
                "getInstance", "()Lcom/fs/starfarer/launcher/ModManager;", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/fs/starfarer/launcher/ModManager",
                "getEnabledModPlugins", "()Ljava/util/List;", false);
        method.visitInsn(Opcodes.POP);

        Label plugin = new Label();
        method.visitLabel(plugin);
        if (includePluginCallback) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/ModPlugin",
                    "onApplicationLoad", "()V", true);
        }
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        method.visitJumpInsn(Opcodes.IFNE, plugin);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        MethodVisitor background = writer.visitMethod(
                Opcodes.ACC_PRIVATE, "renderBg", "()V", null, null);
        background.visitCode();
        background.visitInsn(Opcodes.RETURN);
        background.visitMaxs(0, 0);
        background.visitEnd();

        MethodVisitor progress = writer.visitMethod(
                Opcodes.ACC_PRIVATE, "renderProgress", "(F)V", null, null);
        progress.visitCode();
        progress.visitInsn(Opcodes.RETURN);
        progress.visitMaxs(0, 0);
        progress.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodNode init(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(method -> StartupPhasePlan.INIT_METHOD.equals(method.name)
                        && StartupPhasePlan.INIT_DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static MethodNode progressMethod(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(method -> "renderProgress".equals(method.name) && "(F)V".equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static List<String> phaseNames(MethodNode method) {
        List<String> names = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof LdcInsnNode constant
                    && constant.cst instanceof String name
                    && instruction.getNext() instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner) && "mark".equals(call.name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static int runtimeCalls(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner) && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasDupImmediatelyBeforePluginStart(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner) && "pluginStart".equals(call.name)) {
                return instruction.getPrevious() != null
                        && instruction.getPrevious().getOpcode() == Opcodes.DUP;
            }
        }
        return false;
    }
}
