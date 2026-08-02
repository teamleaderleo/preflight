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

class RuleCommandClassCachePlanTest {
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/RuleCommandClassCacheRuntime";

    @Test
    void addsTheShortcutAndBothLearningHooksWithoutDisturbingTheWalk() throws Exception {
        byte[] original = RuleCommandLookupFixture.vanilla();

        byte[] rewritten = RuleCommandClassCachePlan.transform(
                ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(1, calls(rewritten, RUNTIME, "shortcut"));
        assertEquals(1, calls(rewritten, RUNTIME, "noteFailure"));
        assertEquals(1, calls(rewritten, RUNTIME, "noteWinner"));
        // The walk itself is untouched: one lookup, one instantiation, one memoisation, and the
        // shortcut's own memoisation is the only thing that adds to those counts.
        assertEquals(1, calls(rewritten, "java/lang/Class", "forName"));
        assertEquals(1, calls(rewritten, "java/lang/Class", "newInstance"));
        assertEquals(2, calls(rewritten, "java/util/Map", "put"));
    }

    @Test
    void refusesToWeaveTwice() throws Exception {
        byte[] original = RuleCommandLookupFixture.vanilla();
        byte[] once = RuleCommandClassCachePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(once);
        assertNull(RuleCommandClassCachePlan.transform(ClassSignature.parse(once), once));
    }

    @Test
    void declinesEveryShapeItHasNotReviewed() throws Exception {
        // Two lookups in one method: which one the prepared answer replaces is not decidable here.
        byte[] ambiguous = RuleCommandLookupFixture.generate(2, 1, "java/lang/Exception");
        assertNull(RuleCommandClassCachePlan.transform(ClassSignature.parse(ambiguous), ambiguous));

        // A handler that catches more than vanilla catches would change which failures continue the
        // walk, so the learning run could no longer tell a clean prefix from a dirty one.
        byte[] broaderCatch = RuleCommandLookupFixture.generate(1, 1, "java/lang/Throwable");
        assertNull(RuleCommandClassCachePlan.transform(
                ClassSignature.parse(broaderCatch), broaderCatch));

        byte[] twoHandlers = RuleCommandLookupFixture.generate(1, 2, "java/lang/Exception");
        assertNull(RuleCommandClassCachePlan.transform(
                ClassSignature.parse(twoHandlers), twoHandlers));
    }

    @Test
    void declinesAClassThatOnlyLooksLikeTheTarget() throws Exception {
        byte[] unrelated = unrelatedClass();
        assertNull(RuleCommandClassCachePlan.transform(ClassSignature.parse(unrelated), unrelated));
    }

    @Test
    void publishesFromTheRulesLoaderReturnAndRefusesToDoItTwice() throws Exception {
        byte[] loader = rulesLoader();

        byte[] once = RuleCommandClassCachePlan.transformLoader(
                ClassSignature.parse(loader), loader);

        assertNotNull(once);
        assertEquals(1, calls(once, RUNTIME, "complete"));
        assertNull(RuleCommandClassCachePlan.transformLoader(ClassSignature.parse(once), once));
    }

    private static byte[] rulesLoader() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RulesLoaderPhasePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                RulesLoaderPhasePlan.LOAD_METHOD, RulesLoaderPhasePlan.LOAD_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] unrelatedClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/NotTheGame",
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                RuleCommandClassCachePlan.LOOKUP_METHOD,
                RuleCommandClassCachePlan.LOOKUP_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int calls(byte[] bytes, String owner, String name) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, 0);
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && owner.equals(call.owner) && name.equals(call.name)) {
                    count++;
                }
            }
        }
        return count;
    }
}
