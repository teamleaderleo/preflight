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

class ResourceProbePlanTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/ResourceProbeRuntime";

    @Test
    void routesEveryProbeThroughTheRuntime() throws Exception {
        byte[] original = fixture(ResourceProbePlan.EXPECTED_CALL_SITES);
        byte[] rewritten = ResourceProbePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(0, calls(rewritten, Opcodes.INVOKEVIRTUAL, "java/io/File", "exists"),
                "no direct File.exists may survive, or part of the walk still pays for a syscall");
        assertEquals(ResourceProbePlan.EXPECTED_CALL_SITES,
                calls(rewritten, Opcodes.INVOKESTATIC, RUNTIME, "exists"));
    }

    @Test
    void declinesAClassWithADifferentNumberOfProbeSites() throws Exception {
        // A build with more or fewer probes is a build this rewrite was not reviewed against, and
        // the adapter's whole contract is that an unreviewed shape declines rather than adapts.
        for (int sites : new int[] {ResourceProbePlan.EXPECTED_CALL_SITES - 1,
                ResourceProbePlan.EXPECTED_CALL_SITES + 1}) {
            byte[] original = fixture(sites);
            assertNull(ResourceProbePlan.transform(ClassSignature.parse(original), original),
                    "expected a decline for " + sites + " probe sites");
        }
    }

    @Test
    void declinesAClassItHasAlreadyRewritten() throws Exception {
        byte[] original = fixture(ResourceProbePlan.EXPECTED_CALL_SITES);
        byte[] once = ResourceProbePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(ResourceProbePlan.transform(ClassSignature.parse(once), once));
    }

    @Test
    void declinesAClassWithoutTheResolverMethod() throws Exception {
        byte[] other = fixture(ResourceProbePlan.EXPECTED_CALL_SITES, "com/fs/util/Other");
        assertNull(ResourceProbePlan.transform(ClassSignature.parse(other), other));
    }

    private static byte[] fixture(int probeSites) {
        return fixture(probeSites, ResourceProbePlan.TARGET_CLASS);
    }

    /**
     * The shape the resolver uses at every root: build a {@code File}, ask whether it is there.
     * Stack-neutral substitution means the fixture needs nothing more elaborate than that.
     */
    private static byte[] fixture(int probeSites, String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor resolve = writer.visitMethod(Opcodes.ACC_PUBLIC,
                ResourceProbePlan.RESOLVE_METHOD, ResourceProbePlan.RESOLVE_DESCRIPTOR, null, null);
        resolve.visitCode();
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
        resolve.visitMaxs(4, 3);
        resolve.visitEnd();

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
