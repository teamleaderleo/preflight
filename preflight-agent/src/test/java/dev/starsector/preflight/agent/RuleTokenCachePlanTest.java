package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandle;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class RuleTokenCachePlanTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/RuleTokenCacheRuntime";

    @Test
    void routesTheTokenizerThroughTheRuntimeAndKeepsItReachable() throws Exception {
        byte[] original = fixture(Opcodes.V17, 1);
        byte[] rewritten = RuleTokenCachePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(0, invokeStatics(rewritten, RuleTokenCachePlan.TOKENIZE_OWNER),
                "the direct call must be replaced, not duplicated");
        assertEquals(1, invokeStatics(rewritten, RUNTIME));

        // The fallback has to survive as a MethodHandle constant: that is what lets the runtime
        // decline any expression it cannot serve, and what makes a game without that method fail to
        // link here rather than silently lose its tokenizer.
        Handle handle = handleConstant(rewritten);
        assertNotNull(handle, "vanilla must be reachable as a MethodHandle constant");
        assertEquals(Opcodes.H_INVOKESTATIC, handle.getTag());
        assertEquals(RuleTokenCachePlan.TOKENIZE_OWNER, handle.getOwner());
        assertEquals(RuleTokenCachePlan.TOKENIZE_NAME, handle.getName());
        assertEquals(RuleTokenCachePlan.TOKENIZE_DESCRIPTOR, handle.getDesc());
        assertTrue(hasClassConstant(rewritten, RuleTokenCachePlan.TOKEN_CLASS),
                "the token class must be pushed rather than looked up by name at runtime");
    }

    @Test
    void addsNoBranchesSoTheOriginalStackMapStillDescribesTheMethod() throws Exception {
        byte[] original = fixture(Opcodes.V17, 1);
        byte[] rewritten = RuleTokenCachePlan.transform(ClassSignature.parse(original), original);

        MethodNode before = constructor(original);
        MethodNode after = constructor(rewritten);
        assertEquals(jumps(before), jumps(after), "the rewrite must not add control flow");
        assertEquals(before.instructions.size() + 2, after.instructions.size(),
                "two constants in, one call swapped in place");
    }

    @Test
    void refusesChangedShapesAndDoubleWeaving() throws Exception {
        byte[] noTokenizer = fixture(Opcodes.V17, 0);
        assertNull(RuleTokenCachePlan.transform(ClassSignature.parse(noTokenizer), noTokenizer));

        byte[] twoTokenizers = fixture(Opcodes.V17, 2);
        assertNull(RuleTokenCachePlan.transform(ClassSignature.parse(twoTokenizers), twoTokenizers));

        byte[] original = fixture(Opcodes.V17, 1);
        byte[] once = RuleTokenCachePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(RuleTokenCachePlan.transform(ClassSignature.parse(once), once));
    }

    @Test
    void declinesAClassTooOldToCarryAMethodHandleConstant() throws Exception {
        byte[] java6 = fixture(Opcodes.V1_6, 1);
        assertNull(RuleTokenCachePlan.transform(ClassSignature.parse(java6), java6),
                "raising the class version would change how it is verified");
    }

    @Test
    void chainsAfterTheAttributionProbeWithoutDisturbingIt() throws Exception {
        byte[] original = fixture(Opcodes.V17, 1);
        byte[] attributed = RuleExpressionPhasePlan.transform(
                ClassSignature.parse(withCommandCalls(original)), withCommandCalls(original));
        assertNotNull(attributed, "the probe must apply to the reviewed shape first");

        byte[] cached = RuleTokenCachePlan.transform(ClassSignature.parse(attributed), attributed);

        assertNotNull(cached, "the memo must still find the tokenizer inside a probed constructor");
        assertEquals(1, invokeStatics(cached, RUNTIME));
        // Seven label switches plus the close before the return.
        assertEquals(8, invokeStatics(cached,
                "dev/starsector/preflight/agent/StartupPhaseRuntime"),
                "and must leave every subphase label in place");
    }

    // -----------------------------------------------------------------------------------------

    private static byte[] fixture(int version, int tokenizeCalls) {
        return fixture(version, tokenizeCalls, 0);
    }

    /** The reviewed constructor's shape, optionally with the two command-class branches. */
    private static byte[] fixture(int version, int tokenizeCalls, int commandClassCalls) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(version, Opcodes.ACC_PUBLIC, RuleTokenCachePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
                RuleTokenCachePlan.LOAD_METHOD, RuleTokenCachePlan.LOAD_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        for (int index = 0; index < tokenizeCalls; index++) {
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, RuleTokenCachePlan.TOKENIZE_OWNER,
                    RuleTokenCachePlan.TOKENIZE_NAME, RuleTokenCachePlan.TOKENIZE_DESCRIPTOR, false);
            method.visitInsn(Opcodes.POP);
        }
        for (int index = 0; index < commandClassCalls; index++) {
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, RuleTokenCachePlan.TARGET_CLASS,
                    "getCommandClass", "(Ljava/lang/String;)Ljava/lang/String;", false);
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] withCommandCalls(byte[] ignored) {
        return fixture(Opcodes.V17, 1, 2);
    }

    private static Handle handleConstant(byte[] bytes) {
        for (AbstractInsnNode instruction : instructions(bytes)) {
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Handle handle) {
                return handle;
            }
        }
        return null;
    }

    private static boolean hasClassConstant(byte[] bytes, String internalName) {
        for (AbstractInsnNode instruction : instructions(bytes)) {
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Type type
                    && internalName.equals(type.getInternalName())) {
                return true;
            }
        }
        return false;
    }

    private static int invokeStatics(byte[] bytes, String owner) {
        int count = 0;
        for (AbstractInsnNode instruction : instructions(bytes)) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC && owner.equals(call.owner)) {
                count++;
            }
        }
        return count;
    }

    private static int jumps(MethodNode method) {
        int count = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof org.objectweb.asm.tree.JumpInsnNode) {
                count++;
            }
        }
        return count;
    }

    private static Iterable<AbstractInsnNode> instructions(byte[] bytes) {
        return () -> constructor(bytes).instructions.iterator();
    }

    private static MethodNode constructor(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : owner.methods) {
            if (RuleTokenCachePlan.LOAD_METHOD.equals(method.name)
                    && RuleTokenCachePlan.LOAD_DESCRIPTOR.equals(method.desc)) {
                return method;
            }
        }
        throw new AssertionError("no constructor");
    }

    @SuppressWarnings("unused")
    private static MethodHandle unusedTypeAnchor() {
        return null;
    }
}
