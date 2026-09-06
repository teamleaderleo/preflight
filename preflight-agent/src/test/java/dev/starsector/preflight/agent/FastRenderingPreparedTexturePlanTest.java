package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

class FastRenderingPreparedTexturePlanTest {
    private static final String RUNTIME =
            FastRenderingPreparedTextureRuntime.class.getName().replace('.', '/');

    @Test
    void insertsOneFailOpenShortcutAndRefusesShapeDriftOrASecondWeave() throws Exception {
        byte[] original = fixture(true);
        byte[] transformed = FastRenderingPreparedTexturePlan.transform(
                ClassSignature.parse(original), original);

        assertNotNull(transformed);
        assertEquals(1, calls(transformed, RUNTIME, "load"));
        assertNull(FastRenderingPreparedTexturePlan.transform(
                ClassSignature.parse(transformed), transformed));
        assertNull(FastRenderingPreparedTexturePlan.transform(
                ClassSignature.parse(fixture(false)), fixture(false)));

        ClassNode owner = read(transformed);
        for (var method : owner.methods) {
            new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method);
        }
    }

    @Test
    void targetPinsTheReviewedClassArchiveAndLoader() {
        AdapterTarget target = AdapterTargetRegistry.fastRenderingPreparedTextureTarget();
        ClassSignature signature = new ClassSignature(
                FastRenderingPreparedTexturePlan.TARGET_CLASS,
                FastRenderingPreparedTexturePlan.TARGET_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(
                        FastRenderingPreparedTexturePlan.TARGET_METHOD,
                        FastRenderingPreparedTexturePlan.TARGET_DESCRIPTOR,
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)));
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                "file:C:/Starsector/fr.jar",
                "C:/Starsector/fr.jar",
                "FAST_RENDERING",
                FastRenderingPreparedTexturePlan.SOURCE_SHA256,
                "",
                "jdk/internal/loader/ClassLoaders$AppClassLoader",
                "app");

        assertTrue(target.match(signature, source).exact());
    }

    private static byte[] fixture(boolean includeDds) {
        return fixture(includeDds, false);
    }

    @Test
    void portPreservesBlacklistPolicyAndDeclinesShapeDrift() throws Exception {
        byte[] original = fixture(true, true);
        byte[] transformed = FastRenderingPreparedTexturePlan.transformPort(ClassSignature.parse(original), original);
        assertNotNull(transformed);
        assertEquals(1, calls(transformed, RUNTIME, "loadPort"));
        assertEquals(1, calls(transformed,
                "com/genir/renderer/overrides/loading/textures/Blacklist", "doNotModify"));
        assertNull(FastRenderingPreparedTexturePlan.transformPort(ClassSignature.parse(transformed), transformed));
        byte[] missingDds = fixture(false, true);
        assertNull(FastRenderingPreparedTexturePlan.transformPort(ClassSignature.parse(missingDds), missingDds));
        ClassNode owner = read(transformed);
        for (var method : owner.methods) {
            new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method);
        }
        ClassNode changed = read(original);
        for (var method : changed.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof org.objectweb.asm.tree.VarInsnNode store
                        && store.getOpcode() == Opcodes.ISTORE && store.var == 3) store.var = 4;
            }
        }
        ClassWriter writer = new ClassWriter(0);
        changed.accept(writer);
        byte[] drift = writer.toByteArray();
        assertNull(FastRenderingPreparedTexturePlan.transformPort(ClassSignature.parse(drift), drift));
    }

    private static byte[] fixture(boolean includeDds, boolean port) {
        String data = port ? FastRenderingPreparedTexturePlan.PORT_DATA
                : "com/genir/renderer/overrides/loading/TextureData";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                port ? FastRenderingPreparedTexturePlan.PORT_CLASS : FastRenderingPreparedTexturePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                FastRenderingPreparedTexturePlan.TARGET_METHOD,
                port ? FastRenderingPreparedTexturePlan.PORT_DESCRIPTOR : FastRenderingPreparedTexturePlan.TARGET_DESCRIPTOR,
                null,
                null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "com/genir/renderer/overrides/loading/FileLoader",
                "loadInputStream",
                "(Ljava/lang/String;Z)Ljava/io/InputStream;",
                false);
        method.visitVarInsn(Opcodes.ASTORE, 2);
        if (port) {
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/genir/renderer/overrides/loading/textures/Blacklist", "doNotModify",
                    "(Ljava/lang/String;)Z", false);
            method.visitVarInsn(Opcodes.ISTORE, 3);
        }
        if (includeDds) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    port ? "com/genir/renderer/overrides/loading/textures/DDSIntegration"
                            : "com/genir/renderer/overrides/loading/DDSCache",
                    "getTexture",
                    "(Ljava/nio/file/Path;)L" + data + ";",
                    false);
            method.visitInsn(Opcodes.POP);
        }
        method.visitTypeInsn(Opcodes.NEW, "java/io/BufferedInputStream");
        method.visitInsn(Opcodes.DUP);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/io/BufferedInputStream",
                "<init>",
                "(Ljava/io/InputStream;)V",
                false);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "javax/imageio/ImageIO",
                "read",
                "(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;",
                false);
        if (port) method.visitVarInsn(Opcodes.ILOAD, 3);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                port ? "com/genir/renderer/overrides/loading/textures/TextureBuilder"
                        : "com/genir/renderer/overrides/TextureBuilder",
                "readAndAnalyzeImage",
                "(Ljava/awt/image/BufferedImage;" + (port ? "Z" : "") + ")L" + data + ";",
                false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int calls(byte[] bytes, String owner, String name) {
        int count = 0;
        for (var method : read(bytes).methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && owner.equals(call.owner) && name.equals(call.name)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }
}
