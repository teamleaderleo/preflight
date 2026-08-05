package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

class VersionCheckResponseDedupPlanTest {
    private static final String RUNTIME =
            VersionCheckResponseDedupRuntime.class.getName().replace('.', '/');

    @BeforeEach
    void reset() {
        VersionCheckResponseDedupRuntime.reset();
    }

    @Test
    void rewritesBothReviewedReadsForEachVersionCheckerFork() {
        assertRewritten(
                VersionCheckResponseDedupPlan.LUNA_CLASS,
                VersionCheckResponseDedupPlan.LUNA_SHA256);
        assertRewritten(
                VersionCheckResponseDedupPlan.NEX_CLASS,
                VersionCheckResponseDedupPlan.NEX_SHA256);
        assertEquals(2L,
                VersionCheckResponseDedupRuntime.telemetry().get("installedTargets"));
    }

    @Test
    void changedShapeWrongHashAndSecondRewriteFailClosed() {
        ClassSignature exact = signature(
                VersionCheckResponseDedupPlan.LUNA_CLASS,
                VersionCheckResponseDedupPlan.LUNA_SHA256);
        assertNull(VersionCheckResponseDedupPlan.transform(
                exact, fixture(VersionCheckResponseDedupPlan.LUNA_CLASS, true)));
        ClassSignature wrong = new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(), exact.methods());
        assertNull(VersionCheckResponseDedupPlan.transform(
                wrong, fixture(VersionCheckResponseDedupPlan.LUNA_CLASS, false)));

        byte[] transformed = VersionCheckResponseDedupPlan.transform(
                exact, fixture(VersionCheckResponseDedupPlan.LUNA_CLASS, false));
        assertNotNull(transformed);
        assertNull(VersionCheckResponseDedupPlan.transform(exact, transformed));
    }

    @Test
    void targetsPinBothReviewedArchivesAndTheModLoader() {
        assertExactTarget(
                AdapterTargetRegistry.lunaVersionCheckResponseDedupTarget(),
                signature(VersionCheckResponseDedupPlan.LUNA_CLASS,
                        VersionCheckResponseDedupPlan.LUNA_SHA256),
                "/game/mods/LunaLib/jars/LunaLib.jar");
        assertExactTarget(
                AdapterTargetRegistry.nexVersionCheckResponseDedupTarget(),
                signature(VersionCheckResponseDedupPlan.NEX_CLASS,
                        VersionCheckResponseDedupPlan.NEX_SHA256),
                "/game/mods/Nexerelin/jars/ExerelinCore.jar");
    }

    private static void assertRewritten(String className, String hash) {
        byte[] transformed = VersionCheckResponseDedupPlan.transform(
                signature(className, hash), fixture(className, false));
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        assertEquals(0, calls(owner, "java/net/URL", "openStream"));
        assertEquals(2, calls(owner, RUNTIME, "openStream"));
    }

    private static void assertExactTarget(
            AdapterTarget target, ClassSignature signature, String sourcePath) {
        assertTrue(AdapterTransformationRegistry.hasPlan(target.planId()));
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                "file:" + sourcePath,
                sourcePath.toLowerCase(java.util.Locale.ROOT),
                "MOD",
                target.sourceSha256(),
                "",
                "java/net/URLClassLoader",
                null);
        assertTrue(target.match(signature, source).exact());
    }

    private static ClassSignature signature(String className, String hash) {
        return new ClassSignature(
                className,
                hash,
                61,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                List.of(
                        new ClassSignature.Method(
                                VersionCheckResponseDedupPlan.REMOTE_METHOD,
                                VersionCheckResponseDedupPlan.REMOTE_DESCRIPTOR,
                                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC),
                        new ClassSignature.Method(
                                VersionCheckResponseDedupPlan.LATEST_METHOD,
                                VersionCheckResponseDedupPlan.LATEST_DESCRIPTOR,
                                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)));
    }

    private static byte[] fixture(String className, boolean extraRead) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                className, null, "java/lang/Object", null);

        MethodNode remote = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                VersionCheckResponseDedupPlan.REMOTE_METHOD,
                VersionCheckResponseDedupPlan.REMOTE_DESCRIPTOR,
                null,
                null);
        remote.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/net/URL"));
        remote.instructions.add(new InsnNode(Opcodes.DUP));
        remote.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        remote.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/net/URL", "<init>", "(Ljava/lang/String;)V", false));
        remote.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/net/URL", "openStream", "()Ljava/io/InputStream;", false));
        remote.instructions.add(new InsnNode(Opcodes.ARETURN));
        remote.accept(writer);

        MethodNode latest = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                VersionCheckResponseDedupPlan.LATEST_METHOD,
                VersionCheckResponseDedupPlan.LATEST_DESCRIPTOR,
                null,
                null);
        addConstantRead(latest);
        latest.instructions.add(new InsnNode(Opcodes.POP));
        if (extraRead) {
            addConstantRead(latest);
            latest.instructions.add(new InsnNode(Opcodes.POP));
        }
        latest.instructions.add(new LdcInsnNode("0.98a"));
        latest.instructions.add(new InsnNode(Opcodes.ARETURN));
        latest.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addConstantRead(MethodNode method) {
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/net/URL"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new LdcInsnNode("https://version.test/file"));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/net/URL", "<init>", "(Ljava/lang/String;)V", false));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/net/URL", "openStream", "()Ljava/io/InputStream;", false));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static int calls(ClassNode owner, String callOwner, String name) {
        int count = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && callOwner.equals(call.owner) && name.equals(call.name)) {
                    count++;
                }
            }
        }
        return count;
    }
}
