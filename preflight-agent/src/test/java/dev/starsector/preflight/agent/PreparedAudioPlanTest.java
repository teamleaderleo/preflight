package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class PreparedAudioPlanTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/PreparedAudioRuntime";

    @Test
    void windowsRequiresExactBytesAndPreparedCacheTakesPrecedenceOverChunkCopy() throws Exception {
        byte[] unknown = fixture("sound/O0oO");
        assertNull(PreparedAudioPlan.transformWindows(ClassSignature.parse(unknown), unknown));
        String saved = System.getProperty(WindowsPcmCopyRuntime.PROPERTY);
        try {
            System.setProperty(WindowsPcmCopyRuntime.PROPERTY, "true");
            var registry = AdapterTargetRegistry.empty()
                    .withTextureTarget(TextureAdapterMode.PREPARED_PIXELS).withPreparedAudioTarget();
            assertEquals(0, registry.targets().stream()
                    .filter(t -> t.planId().equals(WindowsPcmCopyRuntime.PLAN_ID)).count());
            assertEquals(1, registry.targets().stream()
                    .filter(t -> t.id().equals("windows-ogg-decoder-0.98a-rc8-prepared-audio")).count());
        } finally {
            if (saved == null) System.clearProperty(WindowsPcmCopyRuntime.PROPERTY);
            else System.setProperty(WindowsPcmCopyRuntime.PROPERTY, saved);
        }
    }

    @Test
    void putsTheRuntimeInFrontOfTheDecodeAndKeepsTheOriginal() throws Exception {
        byte[] original = fixture(PreparedAudioPlan.TARGET_CLASS);
        byte[] rewritten = PreparedAudioPlan.transform(ClassSignature.parse(original), original);
        assertNotNull(rewritten);

        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(rewritten).accept(node, 0);

        MethodNode entry = method(node, PreparedAudioPlan.DECODE_METHOD);
        assertNotNull(entry, "the original name must still be the one the loader reaches");
        assertEquals(PreparedAudioPlan.DECODE_DESCRIPTOR, entry.desc);
        assertEquals(1, calls(rewritten, Opcodes.INVOKESTATIC, RUNTIME, "decode"));

        MethodNode vanilla = method(node, PreparedAudioPlan.VANILLA_METHOD);
        assertNotNull(vanilla, "vanilla must survive under a new name for the runtime to fall back to");
        assertEquals(PreparedAudioPlan.DECODE_DESCRIPTOR, vanilla.desc);
        assertEquals(Opcodes.ACC_PUBLIC, vanilla.access & Opcodes.ACC_PUBLIC,
                "the runtime reaches it through a MethodHandle, so it cannot stay private");
    }

    @Test
    void declinesAClassItHasAlreadyRewritten() throws Exception {
        byte[] original = fixture(PreparedAudioPlan.TARGET_CLASS);
        byte[] once = PreparedAudioPlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(PreparedAudioPlan.transform(ClassSignature.parse(once), once));
    }

    @Test
    void declinesAClassThatIsNotTheDecoder() throws Exception {
        byte[] other = fixture("sound/NotTheDecoder");
        assertNull(PreparedAudioPlan.transform(ClassSignature.parse(other), other));
    }

    private static MethodNode method(ClassNode node, String name) {
        for (MethodNode candidate : node.methods) {
            if (name.equals(candidate.name)) {
                return candidate;
            }
        }
        return null;
    }

    /** One instance method with the decoder's exact name and descriptor is the whole contract. */
    private static byte[] fixture(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor decode = writer.visitMethod(Opcodes.ACC_PUBLIC,
                PreparedAudioPlan.DECODE_METHOD, PreparedAudioPlan.DECODE_DESCRIPTOR, null,
                new String[] {"java/io/IOException"});
        decode.visitCode();
        decode.visitInsn(Opcodes.ACONST_NULL);
        decode.visitInsn(Opcodes.ARETURN);
        decode.visitMaxs(1, 2);
        decode.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int calls(byte[] bytes, int opcode, String owner, String name) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, 0);
        int found = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == opcode
                        && owner.equals(call.owner)
                        && name.equals(call.name)) {
                    found++;
                }
            }
        }
        return found;
    }
}
