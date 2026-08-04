package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class SourceHintIsolationPlanTest {
    private static final String RUNTIME =
            SourceHintIsolationRuntime.class.getName().replace('.', '/');

    @AfterEach
    void reset() {
        SourceHintIsolationRuntime.reset();
        ResourceProbeRuntime.enable(false);
        ResourceProbeRuntime.reset();
    }

    @Test
    void replacesTheSharedFieldTransactionWithAThreadLocalOneShot() throws Exception {
        byte[] original = fixture(0, false);
        byte[] rewritten = SourceHintIsolationPlan.transform(
                exactSignature(original), original);
        assertNotNull(rewritten);

        ClassNode owner = read(rewritten);
        MethodNode setter = method(owner, SourceHintIsolationPlan.SET_METHOD,
                SourceHintIsolationPlan.SET_DESCRIPTOR);
        MethodNode resolver = method(owner, SourceHintIsolationPlan.RESOLVE_METHOD,
                SourceHintIsolationPlan.RESOLVE_DESCRIPTOR);
        assertEquals(1, calls(setter, RUNTIME, "set"));
        assertEquals(1, calls(resolver, RUNTIME, "take"));
        assertEquals(0, hintFieldAccesses(setter));
        assertEquals(0, hintFieldAccesses(resolver));
    }

    @Test
    void declinesAnAlreadyRewrittenOrDifferentShape() throws Exception {
        byte[] original = fixture(0, false);
        byte[] rewritten = SourceHintIsolationPlan.transform(
                exactSignature(original), original);
        assertNotNull(rewritten);
        assertNull(SourceHintIsolationPlan.transform(ClassSignature.parse(rewritten), rewritten));

        byte[] wrong = fixture(0, true);
        assertNull(SourceHintIsolationPlan.transform(exactSignature(wrong), wrong));

        assertNull(SourceHintIsolationPlan.transform(ClassSignature.parse(original), original),
                "fixture bytes do not carry the reviewed installed-class hash");
    }

    @Test
    void differentThreadsCannotConsumeEachOthersHints() throws Exception {
        CountDownLatch bothSet = new CountDownLatch(2);
        CountDownLatch betaTaken = new CountDownLatch(1);
        AtomicReference<String> alpha = new AtomicReference<>();
        AtomicReference<String> beta = new AtomicReference<>();

        Thread first = new Thread(() -> {
            SourceHintIsolationRuntime.set("Alpha");
            bothSet.countDown();
            await(bothSet);
            await(betaTaken);
            alpha.set(SourceHintIsolationRuntime.take());
        }, "source-alpha");
        Thread second = new Thread(() -> {
            SourceHintIsolationRuntime.set("Beta");
            bothSet.countDown();
            await(bothSet);
            beta.set(SourceHintIsolationRuntime.take());
            betaTaken.countDown();
        }, "source-beta");
        first.start();
        second.start();
        first.join();
        second.join();

        assertEquals("Alpha", alpha.get());
        assertEquals("Beta", beta.get());
        assertNull(SourceHintIsolationRuntime.take(), "a taken hint remains one-shot");
    }

    @Test
    void composesWithTheExplicitResourceProbeCache() throws Exception {
        ResourceProbeRuntime.enable(true);
        byte[] original = fixture(ResourceProbePlan.EXPECTED_CALL_SITES, false);
        byte[] rewritten = AdapterTransformationRegistry.transform(
                AdapterTargetRegistry.sourceHintIsolationTarget(),
                exactSignature(original), original);
        assertNotNull(rewritten);

        ClassNode owner = read(rewritten);
        assertEquals(1, calls(method(owner, SourceHintIsolationPlan.SET_METHOD,
                SourceHintIsolationPlan.SET_DESCRIPTOR), RUNTIME, "set"));
        assertEquals(ResourceProbePlan.EXPECTED_CALL_SITES,
                calls(owner, "dev/starsector/preflight/agent/ResourceProbeRuntime", "exists"));
        assertNotNull(method(owner, ResourceProbePlan.VANILLA_OPEN_METHOD,
                ResourceProbePlan.OPEN_DESCRIPTOR));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static byte[] fixture(int probeSites, boolean wrongSetterShape) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, SourceHintIsolationPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        FieldVisitor hint = writer.visitField(Opcodes.ACC_PRIVATE, "String",
                "Ljava/lang/String;", null, null);
        hint.visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V",
                null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V",
                false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor setter = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNCHRONIZED,
                SourceHintIsolationPlan.SET_METHOD, SourceHintIsolationPlan.SET_DESCRIPTOR,
                null, null);
        setter.visitCode();
        setter.visitVarInsn(Opcodes.ALOAD, 0);
        setter.visitVarInsn(Opcodes.ALOAD, wrongSetterShape ? 0 : 1);
        setter.visitFieldInsn(Opcodes.PUTFIELD, SourceHintIsolationPlan.TARGET_CLASS, "String",
                "Ljava/lang/String;");
        setter.visitInsn(Opcodes.RETURN);
        setter.visitMaxs(2, 2);
        setter.visitEnd();

        MethodVisitor resolve = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNCHRONIZED,
                SourceHintIsolationPlan.RESOLVE_METHOD, SourceHintIsolationPlan.RESOLVE_DESCRIPTOR,
                null, new String[] {"java/io/IOException"});
        resolve.visitCode();
        resolve.visitVarInsn(Opcodes.ALOAD, 0);
        resolve.visitFieldInsn(Opcodes.GETFIELD, SourceHintIsolationPlan.TARGET_CLASS, "String",
                "Ljava/lang/String;");
        resolve.visitVarInsn(Opcodes.ASTORE, 3);
        resolve.visitVarInsn(Opcodes.ALOAD, 0);
        resolve.visitInsn(Opcodes.ACONST_NULL);
        resolve.visitFieldInsn(Opcodes.PUTFIELD, SourceHintIsolationPlan.TARGET_CLASS, "String",
                "Ljava/lang/String;");
        for (int index = 0; index < probeSites; index++) {
            resolve.visitTypeInsn(Opcodes.NEW, "java/io/File");
            resolve.visitInsn(Opcodes.DUP);
            resolve.visitVarInsn(Opcodes.ALOAD, 1);
            resolve.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/File", "<init>",
                    "(Ljava/lang/String;)V", false);
            resolve.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "exists", "()Z", false);
            resolve.visitInsn(Opcodes.POP);
        }
        resolve.visitInsn(Opcodes.ACONST_NULL);
        resolve.visitInsn(Opcodes.ARETURN);
        resolve.visitMaxs(4, 4);
        resolve.visitEnd();

        MethodVisitor open = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNCHRONIZED,
                ResourceProbePlan.OPEN_METHOD, ResourceProbePlan.OPEN_DESCRIPTOR, null,
                new String[] {"java/io/FileNotFoundException"});
        open.visitCode();
        open.visitInsn(Opcodes.ACONST_NULL);
        open.visitInsn(Opcodes.ARETURN);
        open.visitMaxs(1, 3);
        open.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode result = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(result, ClassReader.EXPAND_FRAMES);
        return result;
    }

    private static ClassSignature exactSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), SourceHintIsolationPlan.ORIGINAL_SHA256,
                parsed.majorVersion(), parsed.access(), parsed.methods());
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                result++;
            }
        }
        return result;
    }

    private static int calls(ClassNode type, String owner, String name) {
        return type.methods.stream().mapToInt(method -> calls(method, owner, name)).sum();
    }

    private static int hintFieldAccesses(MethodNode method) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && SourceHintIsolationPlan.TARGET_CLASS.equals(field.owner)
                    && "String".equals(field.name)) {
                result++;
            }
        }
        return result;
    }
}
