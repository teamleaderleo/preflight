package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

class ResourceProgressRateLimitPlanTest {
    @AfterEach
    void reset() {
        ResourcePriorityRuntime.beginSession();
    }

    @Test
    void reviewedLoaderGetsGuardAndExplicitFinalFrame() {
        ClassNode owner = fixture();
        assertTrue(ResourceProgressRateLimitPlan.apply(signature(), owner));
        MethodNode init = owner.methods.get(0);
        MethodNode render = owner.methods.get(1);
        assertEquals(5, count(init, StartupPhasePlan.TARGET_CLASS, "renderProgress"));
        assertEquals(1, count(render,
                "dev/starsector/preflight/agent/ResourcePriorityRuntime",
                "shouldRenderProgress"));
        assertFalse(ResourceProgressRateLimitPlan.apply(signature(), owner));
    }

    @Test
    void runtimeAlwaysDrawsFirstAndFinalFrames() {
        assertTrue(ResourcePriorityRuntime.shouldRenderProgress(0f));
        boolean skipped = false;
        int attemptedIntermediateFrames = 0;
        while (!skipped && attemptedIntermediateFrames < 100) {
            skipped = !ResourcePriorityRuntime.shouldRenderProgress(0.1f);
            attemptedIntermediateFrames++;
        }
        assertTrue(skipped);
        assertTrue(ResourcePriorityRuntime.shouldRenderProgress(1f));
        assertEquals(2L + attemptedIntermediateFrames,
                ResourcePriorityRuntime.telemetry().get("progressCalls"));
        assertEquals(1L, ResourcePriorityRuntime.telemetry().get("progressSkips"));
        assertEquals(1L + attemptedIntermediateFrames,
                ResourcePriorityRuntime.telemetry().get("progressRenders"));
    }

    private static ClassNode fixture() {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        owner.version = Opcodes.V17;
        owner.access = Opcodes.ACC_PUBLIC;
        owner.name = StartupPhasePlan.TARGET_CLASS;
        owner.superName = "java/lang/Object";
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "init", "(Ljava/util/Map;)V", null, null);
        for (int i = 0; i < 4; i++) {
            init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            init.instructions.add(new InsnNode(Opcodes.FCONST_0));
            init.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner.name,
                    "renderProgress", "(F)V", false));
        }
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        init.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(
                Opcodes.CHECKCAST, "java/util/concurrent/ExecutorService"));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "java/util/concurrent/ExecutorService", "shutdown", "()V", true));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        owner.methods.add(init);
        MethodNode render = new MethodNode(Opcodes.ACC_PRIVATE, "renderProgress", "(F)V", null, null);
        render.instructions.add(new InsnNode(Opcodes.RETURN));
        owner.methods.add(render);
        return owner;
    }

    private static ClassSignature signature() {
        return new ClassSignature(StartupPhasePlan.TARGET_CLASS,
                ResourcePriorityPlan.ORIGINAL_SHA256, 61, Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method("init", "(Ljava/util/Map;)V", Opcodes.ACC_PUBLIC),
                        new ClassSignature.Method("renderProgress", "(F)V", Opcodes.ACC_PRIVATE)));
    }

    private static long count(MethodNode method, String owner, String name) {
        return java.util.Arrays.stream(method.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals(owner) && call.name.equals(name))
                .count();
    }
}
