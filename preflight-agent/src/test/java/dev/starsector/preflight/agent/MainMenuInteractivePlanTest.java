package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

final class MainMenuInteractivePlanTest {
    private static final String RUNTIME = RuntimeSemanticState.class.getName().replace('.', '/');
    private static final String CONTROL_RUNTIME =
            InternalGameControlRuntime.class.getName().replace('.', '/');

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        RuntimeSemanticState.reset();
        InternalGameControlRuntime.reset();
    }

    @Test
    void marksTheExactRemovalOfThePreloadingLabel() throws Exception {
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        byte[] original = fixture();

        byte[] transformed = MainMenuInteractivePlan.transform(
                exactSignature(original, MainMenuInteractivePlan.ORIGINAL_SHA256), original);

        assertNotNull(transformed);
        assertEquals(1, calls(method(transformed), RUNTIME, "mainMenuInteractive"));
        assertEquals(1, calls(method(transformed), CONTROL_RUNTIME, "titleAdvance"));
    }

    @Test
    void declinesDisabledWrongAmbiguousAndAlreadyTransformedInputs() throws Exception {
        byte[] original = fixture();
        assertNull(MainMenuInteractivePlan.transform(
                exactSignature(original, MainMenuInteractivePlan.ORIGINAL_SHA256), original));

        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        assertNull(MainMenuInteractivePlan.transform(ClassSignature.parse(original), original));
        assertNull(MainMenuInteractivePlan.transform(
                exactSignature(ambiguousFixture(), MainMenuInteractivePlan.ORIGINAL_SHA256),
                ambiguousFixture()));

        byte[] transformed = MainMenuInteractivePlan.transform(
                exactSignature(original, MainMenuInteractivePlan.ORIGINAL_SHA256), original);
        assertNotNull(transformed);
        assertNull(MainMenuInteractivePlan.transform(
                exactSignature(transformed, MainMenuInteractivePlan.ORIGINAL_SHA256), transformed));
    }

    private static byte[] fixture() {
        return fixture(1);
    }

    private static byte[] ambiguousFixture() {
        return fixture(2);
    }

    private static byte[] fixture(int removalCalls) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MainMenuInteractivePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor remove = writer.visitMethod(Opcodes.ACC_PUBLIC, "remove",
                "(Lcom/fs/starfarer/ui/c;)V", null, null);
        remove.visitCode();
        remove.visitInsn(Opcodes.RETURN);
        remove.visitMaxs(0, 2);
        remove.visitEnd();
        MethodVisitor advance = writer.visitMethod(Opcodes.ACC_PROTECTED,
                MainMenuInteractivePlan.ADVANCE_METHOD,
                MainMenuInteractivePlan.ADVANCE_DESCRIPTOR, null, null);
        advance.visitCode();
        for (int index = 0; index < removalCalls; index++) {
            advance.visitVarInsn(Opcodes.ALOAD, 0);
            advance.visitInsn(Opcodes.ACONST_NULL);
            advance.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    MainMenuInteractivePlan.TARGET_CLASS, "remove",
                    "(Lcom/fs/starfarer/ui/c;)V", false);
        }
        advance.visitInsn(Opcodes.RETURN);
        advance.visitMaxs(2, 2);
        advance.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exactSignature(byte[] bytes, String hash) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), hash, parsed.majorVersion(),
                parsed.access(), parsed.methods());
    }

    private static MethodNode method(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(candidate -> MainMenuInteractivePlan.ADVANCE_METHOD.equals(candidate.name)
                        && MainMenuInteractivePlan.ADVANCE_DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }
}
