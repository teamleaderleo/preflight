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
                        "progress-100",
                        "audio-workers-complete",
                        "graphics-finalize-complete",
                        "script-store-start",
                        "script-store-complete",
                        "mod-callbacks-start",
                        "mod-callbacks-complete",
                        "resource-init-complete"),
                phaseNames(init));
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

    private static byte[] fixture(boolean includePluginCallback) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, StartupPhasePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC, StartupPhasePlan.INIT_METHOD, StartupPhasePlan.INIT_DESCRIPTOR,
                null, new String[] {"java/lang/Exception"});
        method.visitCode();

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
