package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class TexturePreparedPixelPlanTest {
    @Test
    void rewritesReviewedDecodeConvertCleanupAndDimensionPattern() throws Exception {
        byte[] original = textureLoader(3, true, true);
        byte[] transformed = TexturePreparedPixelPlan.transform(ClassSignature.parse(original), original);

        assertNotNull(transformed);
        ClassNode node = read(transformed);
        assertNotNull(method(node, "preflight$original$decodeImage", TexturePreparedPixelPlan.DECODE_DESCRIPTOR));
        assertNotNull(method(node, "preflight$original$convertPixels", TexturePreparedPixelPlan.CONVERT_DESCRIPTOR));
        assertNotNull(method(node, "preflight$original$cleanupBuffer", TexturePreparedPixelPlan.CLEANUP_DESCRIPTOR));

        MethodNode decode = method(node, TexturePreparedPixelPlan.DECODE_METHOD, TexturePreparedPixelPlan.DECODE_DESCRIPTOR);
        MethodNode directDecode = method(node, "preflight$original$decodeImage", TexturePreparedPixelPlan.DECODE_DESCRIPTOR);
        assertTrue(hasCall(decode, "com/fs/graphics/L", "class"));
        assertTrue(hasCall(decode, "TexturePreparedPixelRuntime", "load"));
        assertFalse(hasCall(directDecode, "com/fs/graphics/L", "class"));
        assertFalse(hasCall(directDecode, "TexturePreparedPixelRuntime", "load"));

        MethodNode convert = method(node, TexturePreparedPixelPlan.CONVERT_METHOD, TexturePreparedPixelPlan.CONVERT_DESCRIPTOR);
        assertEquals(List.of("derived0", "derived1", "derived2"), fieldWrites(convert));
        assertTrue(hasCall(convert, "TexturePreparedPixelRuntime$PreparedPixel", "width"));
        assertTrue(hasCall(convert, "TexturePreparedPixelRuntime$PreparedPixel", "height"));
        assertTrue(hasCall(convert, TexturePreparedPixelPlan.TEXTURE_OBJECT, "setUploadWidth"));
        assertTrue(hasCall(convert, TexturePreparedPixelPlan.TEXTURE_OBJECT, "setUploadHeight"));
        assertTrue(hasCall(convert, "TexturePreparedPixelRuntime$PreparedPixel", "buffer"));

        MethodNode cleanup = method(node, TexturePreparedPixelPlan.CLEANUP_METHOD, TexturePreparedPixelPlan.CLEANUP_DESCRIPTOR);
        assertTrue(hasCall(cleanup, "TexturePreparedPixelRuntime", "release"));
        MethodNode upload = method(node, "uploadForTest", TexturePreparedPixelPlan.CONVERT_DESCRIPTOR);
        assertNotNull(upload);
        assertTrue(hasCall(upload, "TexturePreparedPixelRuntime", "releaseCurrentThreadBuffer"));
        assertTrue(upload.tryCatchBlocks.stream()
                .anyMatch(block -> "java/lang/Throwable".equals(block.type)));
        assertNull(TexturePreparedPixelPlan.transform(ClassSignature.parse(transformed), transformed));
    }

    @Test
    void rejectsAmbiguousOrUnreviewedConversionShape() throws Exception {
        byte[] twoColors = textureLoader(2, true, true);
        byte[] noRasterRead = textureLoader(3, false, true);
        byte[] noDimensions = textureLoader(3, true, false);

        assertNull(TexturePreparedPixelPlan.transform(ClassSignature.parse(twoColors), twoColors));
        assertNull(TexturePreparedPixelPlan.transform(ClassSignature.parse(noRasterRead), noRasterRead));
        assertNull(TexturePreparedPixelPlan.transform(ClassSignature.parse(noDimensions), noDimensions));
    }

    @Test
    void rewritesTheReviewedLinuxObfuscationNames() throws Exception {
        byte[] original = linuxNames(textureLoader(3, true, true));

        byte[] transformed = TexturePreparedPixelPlan.transform(ClassSignature.parse(original), original);

        assertNotNull(transformed);
        ClassNode node = read(transformed);
        assertTrue(hasCall(
                method(node, TexturePreparedPixelPlan.LINUX_DECODE_METHOD,
                        TexturePreparedPixelPlan.DECODE_DESCRIPTOR),
                "TexturePreparedPixelRuntime", "load"));
        assertNotNull(method(node, TexturePreparedPixelPlan.LINUX_CONVERT_METHOD,
                TexturePreparedPixelPlan.CONVERT_DESCRIPTOR));
        assertNotNull(method(node, TexturePreparedPixelPlan.LINUX_CLEANUP_METHOD,
                TexturePreparedPixelPlan.CLEANUP_DESCRIPTOR));
    }

    @Test
    void rewritesTheReviewedWindowsPreloaderName() throws Exception {
        ClassNode windows = read(textureLoader(3, true, true));
        for (MethodNode method : windows.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && "com/fs/graphics/L".equals(call.owner)
                        && "class".equals(call.name)) {
                    call.name = "Õ00000";
                }
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        windows.accept(writer);
        byte[] original = writer.toByteArray();

        byte[] transformed = TexturePreparedPixelPlan.transform(
                ClassSignature.parse(original), original);

        assertNotNull(transformed);
        MethodNode decode = method(read(transformed), TexturePreparedPixelPlan.DECODE_METHOD,
                TexturePreparedPixelPlan.DECODE_DESCRIPTOR);
        assertTrue(hasCall(decode, "com/fs/graphics/L", "Õ00000"));
        assertTrue(hasCall(decode, "TexturePreparedPixelRuntime", "load"));
    }

    private static byte[] linuxNames(byte[] bytes) {
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            if (TexturePreparedPixelPlan.DECODE_METHOD.equals(method.name)
                    && TexturePreparedPixelPlan.DECODE_DESCRIPTOR.equals(method.desc)) {
                method.name = TexturePreparedPixelPlan.LINUX_DECODE_METHOD;
            } else if (TexturePreparedPixelPlan.CONVERT_METHOD.equals(method.name)
                    && TexturePreparedPixelPlan.CONVERT_DESCRIPTOR.equals(method.desc)) {
                method.name = TexturePreparedPixelPlan.LINUX_CONVERT_METHOD;
            } else if (TexturePreparedPixelPlan.CLEANUP_METHOD.equals(method.name)
                    && TexturePreparedPixelPlan.CLEANUP_DESCRIPTOR.equals(method.desc)) {
                method.name = TexturePreparedPixelPlan.LINUX_CLEANUP_METHOD;
            }
        }
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !TexturePreparedPixelPlan.TARGET_CLASS.equals(call.owner)) {
                    continue;
                }
                if (TexturePreparedPixelPlan.CONVERT_METHOD.equals(call.name)
                        && TexturePreparedPixelPlan.CONVERT_DESCRIPTOR.equals(call.desc)) {
                    call.name = TexturePreparedPixelPlan.LINUX_CONVERT_METHOD;
                } else if (TexturePreparedPixelPlan.CLEANUP_METHOD.equals(call.name)
                        && TexturePreparedPixelPlan.CLEANUP_DESCRIPTOR.equals(call.desc)) {
                    call.name = TexturePreparedPixelPlan.LINUX_CLEANUP_METHOD;
                }
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static List<String> fieldWrites(MethodNode method) {
        List<String> writes = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD) {
                writes.add(field.name);
            }
        }
        return List.copyOf(writes);
    }

    private static boolean hasCall(MethodNode method, String ownerFragment, String name) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.contains(ownerFragment)
                    && name.equals(call.name)) {
                return true;
            }
        }
        return false;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
                .findFirst()
                .orElse(null);
    }

    /** Shared with {@link TextureLoaderPlanCompositionTest}, which weaves a fold onto this loader. */
    static byte[] textureLoader(int colorFields, boolean rasterRead, boolean dimensions) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TexturePreparedPixelPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        constructor(writer);

        MethodVisitor decode = writer.visitMethod(
                Opcodes.ACC_PRIVATE,
                TexturePreparedPixelPlan.DECODE_METHOD,
                TexturePreparedPixelPlan.DECODE_DESCRIPTOR,
                null,
                null);
        decode.visitCode();
        decode.visitVarInsn(Opcodes.ALOAD, 1);
        decode.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "com/fs/graphics/L",
                "class",
                TexturePreparedPixelPlan.DECODE_DESCRIPTOR,
                false);
        decode.visitVarInsn(Opcodes.ASTORE, 2);
        decode.visitVarInsn(Opcodes.ALOAD, 2);
        Label direct = new Label();
        decode.visitJumpInsn(Opcodes.IFNULL, direct);
        decode.visitVarInsn(Opcodes.ALOAD, 2);
        decode.visitInsn(Opcodes.ARETURN);
        decode.visitLabel(direct);
        decode.visitInsn(Opcodes.ACONST_NULL);
        decode.visitInsn(Opcodes.ARETURN);
        decode.visitMaxs(0, 0);
        decode.visitEnd();

        MethodVisitor convert = writer.visitMethod(
                Opcodes.ACC_PRIVATE,
                TexturePreparedPixelPlan.CONVERT_METHOD,
                TexturePreparedPixelPlan.CONVERT_DESCRIPTOR,
                null,
                null);
        convert.visitCode();
        if (dimensions) {
            convert.visitVarInsn(Opcodes.ALOAD, 2);
            convert.visitInsn(Opcodes.ICONST_4);
            convert.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    TexturePreparedPixelPlan.TEXTURE_OBJECT,
                    "setUploadWidth",
                    "(I)V",
                    false);
            convert.visitVarInsn(Opcodes.ALOAD, 2);
            convert.visitIntInsn(Opcodes.BIPUSH, 8);
            convert.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    TexturePreparedPixelPlan.TEXTURE_OBJECT,
                    "setUploadHeight",
                    "(I)V",
                    false);
        }
        if (rasterRead) {
            convert.visitVarInsn(Opcodes.ALOAD, 1);
            convert.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/awt/image/BufferedImage",
                    "getData",
                    "()Ljava/awt/image/Raster;",
                    false);
            convert.visitInsn(Opcodes.POP);
        }
        for (int i = 0; i < colorFields; i++) {
            convert.visitVarInsn(Opcodes.ALOAD, 2);
            convert.visitInsn(Opcodes.ACONST_NULL);
            convert.visitFieldInsn(
                    Opcodes.PUTFIELD,
                    TexturePreparedPixelPlan.TEXTURE_OBJECT,
                    "derived" + i,
                    "Ljava/awt/Color;");
        }
        convert.visitInsn(Opcodes.ACONST_NULL);
        convert.visitInsn(Opcodes.ARETURN);
        convert.visitMaxs(0, 0);
        convert.visitEnd();

        MethodVisitor upload = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "uploadForTest",
                TexturePreparedPixelPlan.CONVERT_DESCRIPTOR,
                null,
                null);
        upload.visitCode();
        upload.visitVarInsn(Opcodes.ALOAD, 0);
        upload.visitVarInsn(Opcodes.ALOAD, 1);
        upload.visitVarInsn(Opcodes.ALOAD, 2);
        upload.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                TexturePreparedPixelPlan.TARGET_CLASS,
                TexturePreparedPixelPlan.CONVERT_METHOD,
                TexturePreparedPixelPlan.CONVERT_DESCRIPTOR,
                false);
        upload.visitInsn(Opcodes.ARETURN);
        upload.visitMaxs(0, 0);
        upload.visitEnd();

        MethodVisitor cleanup = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                TexturePreparedPixelPlan.CLEANUP_METHOD,
                TexturePreparedPixelPlan.CLEANUP_DESCRIPTOR,
                null,
                null);
        cleanup.visitCode();
        cleanup.visitInsn(Opcodes.RETURN);
        cleanup.visitMaxs(0, 0);
        cleanup.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void constructor(ClassWriter writer) {
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }
}
