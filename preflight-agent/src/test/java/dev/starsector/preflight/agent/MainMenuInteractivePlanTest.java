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
    void macPublishesUsabilityFromShowAndRetainsOverlayRemovalTelemetry() throws Exception {
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        byte[] original = fixture();

        byte[] transformed = MainMenuInteractivePlan.transform(
                exactSignature(original, MainMenuInteractivePlan.ORIGINAL_SHA256), original);

        assertNotNull(transformed);
        assertEquals(1, calls(showMethod(transformed), RUNTIME, "mainMenuInteractive"));
        assertEquals(0, calls(showMethod(transformed), RUNTIME, "mainMenuOverlayRemoved"));
        assertEquals(0, calls(advanceMethod(transformed), RUNTIME, "mainMenuInteractive"));
        assertEquals(1, calls(advanceMethod(transformed), RUNTIME, "mainMenuOverlayRemoved"));
        assertEquals(1, calls(advanceMethod(transformed), CONTROL_RUNTIME, "titleAdvance"));
    }

    @Test
    void linuxRetainsItsReviewedShowCompletionUsabilitySeam() throws Exception {
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        byte[] original = fixture(
                MainMenuInteractivePlan.LINUX_TARGET_CLASS,
                "(Lcom/fs/starfarer/ui/OO0o;)V",
                1,
                true);

        byte[] transformed = MainMenuInteractivePlan.transform(
                exactSignature(original, MainMenuInteractivePlan.LINUX_ORIGINAL_SHA256), original);

        assertNotNull(transformed);
        assertEquals(1, calls(showMethod(transformed), RUNTIME, "mainMenuInteractive"));
        assertEquals(0, calls(showMethod(transformed), RUNTIME, "mainMenuOverlayRemoved"));
        assertEquals(0, calls(advanceMethod(transformed), CONTROL_RUNTIME, "titleAdvance"));
    }

    @Test
    void windowsPublishesUsabilityFromShowAndRetainsClosedControl() throws Exception {
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        byte[] original = fixture(
                MainMenuInteractivePlan.WINDOWS_TARGET_CLASS,
                "(Lcom/fs/starfarer/ui/c;)V",
                1,
                true);

        byte[] transformed = MainMenuInteractivePlan.transform(
                exactSignature(original, MainMenuInteractivePlan.WINDOWS_ORIGINAL_SHA256), original);

        assertNotNull(transformed);
        assertEquals(1, calls(showMethod(transformed), RUNTIME, "mainMenuInteractive"));
        assertEquals(0, calls(advanceMethod(transformed), RUNTIME, "mainMenuInteractive"));
        assertEquals(1, calls(advanceMethod(transformed), RUNTIME, "mainMenuOverlayRemoved"));
        assertEquals(1, calls(advanceMethod(transformed), CONTROL_RUNTIME, "titleAdvance"));
    }

    @Test
    void declinesDisabledWrongHashWrongClassMissingMethodAmbiguousAndAlreadyTransformedInputs()
            throws Exception {
        byte[] original = fixture();
        assertNull(MainMenuInteractivePlan.transform(
                exactSignature(original, MainMenuInteractivePlan.ORIGINAL_SHA256), original));

        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        assertNull(MainMenuInteractivePlan.transform(ClassSignature.parse(original), original));
        assertNull(MainMenuInteractivePlan.transform(
                exactSignature(fixture("example/WrongTitle", "(Lcom/fs/starfarer/ui/c;)V", 1, true),
                        MainMenuInteractivePlan.ORIGINAL_SHA256),
                fixture("example/WrongTitle", "(Lcom/fs/starfarer/ui/c;)V", 1, true)));
        byte[] missingShow = fixture(
                MainMenuInteractivePlan.TARGET_CLASS, "(Lcom/fs/starfarer/ui/c;)V", 1, false);
        assertNull(MainMenuInteractivePlan.transform(
                exactSignature(missingShow, MainMenuInteractivePlan.ORIGINAL_SHA256), missingShow));
        byte[] ambiguous = fixture(
                MainMenuInteractivePlan.TARGET_CLASS, "(Lcom/fs/starfarer/ui/c;)V", 2, true);
        assertNull(MainMenuInteractivePlan.transform(
                exactSignature(ambiguous, MainMenuInteractivePlan.ORIGINAL_SHA256), ambiguous));

        byte[] transformed = MainMenuInteractivePlan.transform(
                exactSignature(original, MainMenuInteractivePlan.ORIGINAL_SHA256), original);
        assertNotNull(transformed);
        assertNull(MainMenuInteractivePlan.transform(
                exactSignature(transformed, MainMenuInteractivePlan.ORIGINAL_SHA256), transformed));
    }

    private static byte[] fixture() {
        return fixture(MainMenuInteractivePlan.TARGET_CLASS, "(Lcom/fs/starfarer/ui/c;)V", 1, true);
    }

    private static byte[] fixture(
            String targetClass, String removeDescriptor, int removalCalls, boolean includeShow) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, targetClass,
                null, "java/lang/Object", null);
        MethodVisitor remove = writer.visitMethod(Opcodes.ACC_PUBLIC, "remove",
                removeDescriptor, null, null);
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
                    targetClass, "remove", removeDescriptor, false);
        }
        advance.visitInsn(Opcodes.RETURN);
        advance.visitMaxs(2, 2);
        advance.visitEnd();
        if (includeShow) {
            MethodVisitor show = writer.visitMethod(Opcodes.ACC_PUBLIC,
                    MainMenuInteractivePlan.SHOW_METHOD,
                    MainMenuInteractivePlan.SHOW_DESCRIPTOR, null, null);
            show.visitCode();
            for (int index = 0; index < removalCalls; index++) {
                show.visitInsn(Opcodes.RETURN);
            }
            show.visitMaxs(0, 1);
            show.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exactSignature(byte[] bytes, String hash) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), hash, parsed.majorVersion(),
                parsed.access(), parsed.methods());
    }

    private static MethodNode showMethod(byte[] bytes) {
        return method(bytes, MainMenuInteractivePlan.SHOW_METHOD, MainMenuInteractivePlan.SHOW_DESCRIPTOR);
    }

    private static MethodNode advanceMethod(byte[] bytes) {
        return method(bytes, MainMenuInteractivePlan.ADVANCE_METHOD,
                MainMenuInteractivePlan.ADVANCE_DESCRIPTOR);
    }

    private static MethodNode method(byte[] bytes, String name, String descriptor) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
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
