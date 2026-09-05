package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Exact target rewrite for upload-ready prepared pixels below the ImageIO compatibility seam. */
final class TexturePreparedPixelPlan {
    static final String TARGET_CLASS = "com/fs/graphics/TextureLoader";
    static final String TEXTURE_OBJECT = "com/fs/graphics/Object";
    static final String DECODE_METHOD = "Ô00000";
    static final String LINUX_DECODE_METHOD = "String";
    static final String DECODE_DESCRIPTOR = "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;";
    static final String CONVERT_METHOD = "o00000";
    static final String LINUX_CONVERT_METHOD = "super";
    static final String CONVERT_DESCRIPTOR =
            "(Ljava/awt/image/BufferedImage;Lcom/fs/graphics/Object;)Ljava/nio/ByteBuffer;";
    static final String CLEANUP_METHOD = "o00000";
    static final String LINUX_CLEANUP_METHOD = "super";
    static final String CLEANUP_DESCRIPTOR = "(Ljava/nio/ByteBuffer;Ljava/lang/String;)V";

    private static final String ORIGINAL_DECODE = "preflight$original$decodeImage";
    private static final String ORIGINAL_CONVERT = "preflight$original$convertPixels";
    private static final String ORIGINAL_CLEANUP = "preflight$original$cleanupBuffer";
    private static final String RUNTIME = "dev/starsector/preflight/agent/TexturePreparedPixelRuntime";
    private static final String PRELOADER = "com/fs/graphics/L";
    private static final String PRELOADER_METHOD = "class";
    private static final String WINDOWS_PRELOADER_METHOD = "Õ00000";
    private static final String PREPARED_PIXEL = RUNTIME + "$PreparedPixel";
    private static final String DIMENSION_SETTER_DESCRIPTOR = "(I)V";

    private TexturePreparedPixelPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !hasSupportedMethod(signature, DECODE_METHOD, LINUX_DECODE_METHOD, DECODE_DESCRIPTOR)
                || !hasSupportedMethod(signature, CONVERT_METHOD, LINUX_CONVERT_METHOD, CONVERT_DESCRIPTOR)
                || !hasSupportedMethod(signature, CLEANUP_METHOD, LINUX_CLEANUP_METHOD, CLEANUP_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!TARGET_CLASS.equals(owner.name)) {
            return null;
        }

        MethodNode decode = uniqueSupportedMethod(owner, DECODE_METHOD, LINUX_DECODE_METHOD, DECODE_DESCRIPTOR);
        MethodNode convert = uniqueSupportedMethod(owner, CONVERT_METHOD, LINUX_CONVERT_METHOD, CONVERT_DESCRIPTOR);
        MethodNode cleanup = uniqueSupportedMethod(owner, CLEANUP_METHOD, LINUX_CLEANUP_METHOD, CLEANUP_DESCRIPTOR);
        if (!eligibleInstance(decode) || !eligibleInstance(convert) || !eligibleStatic(cleanup)
                || hasMethod(owner, ORIGINAL_DECODE, DECODE_DESCRIPTOR)
                || hasMethod(owner, ORIGINAL_CONVERT, CONVERT_DESCRIPTOR)
                || hasMethod(owner, ORIGINAL_CLEANUP, CLEANUP_DESCRIPTOR)) {
            return null;
        }

        PreloaderHandoff handoff = preloaderHandoff(decode);
        if (handoff == null) {
            return null;
        }

        List<TexturePreparedPixelColorSink.SinkField> colors =
                TexturePreparedPixelColorSink.reviewed(owner, convert);
        DimensionSetters dimensions = dimensionSetters(convert);
        if (colors.size() != 3 || dimensions == null) {
            return null;
        }
        String convertName = convert.name;
        String cleanupName = cleanup.name;
        List<MethodNode> uploadCallers = directConvertCallers(owner, convert, convertName);
        if (uploadCallers.isEmpty()) {
            return null;
        }

        MethodNode originalDecode = directDecodeClone(decode);
        if (originalDecode == null) {
            return null;
        }
        injectPreparedLookup(decode, handoff.directDecode());
        MethodMetadata convertMetadata = rename(
                owner.name, convert, ORIGINAL_CONVERT, convertName, CONVERT_DESCRIPTOR);
        MethodMetadata cleanupMetadata = rename(
                owner.name, cleanup, ORIGINAL_CLEANUP, cleanupName, CLEANUP_DESCRIPTOR);
        owner.methods.add(originalDecode);
        owner.methods.add(convertWrapper(owner.name, convertMetadata, convertName, colors, dimensions));
        owner.methods.add(cleanupWrapper(owner.name, cleanupMetadata, cleanupName));
        uploadCallers.forEach(TexturePreparedPixelPlan::addExceptionalRelease);
        TextureUnpackAlignmentPlan.apply(signature, uploadCallers);
        TextureUploadProbePlan.instrument(uploadCallers);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode convertWrapper(
            String owner,
            MethodMetadata metadata,
            String methodName,
            List<TexturePreparedPixelColorSink.SinkField> colors,
            DimensionSetters dimensions) {
        MethodNode wrapper = method(metadata, methodName, CONVERT_DESCRIPTOR);
        LabelNode ordinary = new LabelNode();
        LabelNode preparedFallback = new LabelNode();
        LabelNode decodeOriginal = new LabelNode();
        LabelNode converted = new LabelNode();
        LabelNode skipDimensionReplay = new LabelNode();

        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "isCarrier",
                "(Ljava/awt/image/BufferedImage;)Z",
                false));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, ordinary));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "prepare",
                "(Ljava/awt/image/BufferedImage;)L" + PREPARED_PIXEL + ";",
                false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFNULL, preparedFallback));

        // The installed converter writes the computed backing dimensions into the texture object
        // before returning its buffer, and those setters are not plain setters: each recomputes a
        // texture-coordinate ratio as source/stored. Replay that exact reviewed side effect for the
        // coherent-direct diagnostic, and also whenever padding removal is on -- there the stored
        // dimension becomes the source dimension, so the ratio has to be recomputed to 1.0. Skipping
        // it would leave a ratio scaled for an allocation that is no longer being made, which is the
        // one way this change corrupts rendering while every reported number looks right.
        LabelNode replayDimensions = new LabelNode();
        wrapper.instructions.add(new LdcInsnNode(TexturePreparedPixelRuntime.COHERENT_DIRECT_PROPERTY));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Boolean",
                "getBoolean",
                "(Ljava/lang/String;)Z",
                false));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFNE, replayDimensions));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/starsector/preflight/agent/TexturePaddingRuntime",
                "enabled",
                "()Z",
                false));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, skipDimensionReplay));
        wrapper.instructions.add(replayDimensions);
        addDimensionSetter(wrapper, dimensions.widthMethod(), "width");
        addDimensionSetter(wrapper, dimensions.heightMethod(), "height");
        wrapper.instructions.add(skipDimensionReplay);

        for (int i = 0; i < colors.size(); i++) {
            TexturePreparedPixelColorSink.SinkField field = colors.get(i);
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, field.receiverLocal()));
            wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
            wrapper.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    PREPARED_PIXEL,
                    "color" + i,
                    "()Ljava/awt/Color;",
                    false));
            wrapper.instructions.add(new FieldInsnNode(
                    Opcodes.PUTFIELD, field.owner(), field.name(), field.descriptor()));
        }
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                PREPARED_PIXEL,
                "buffer",
                "()Ljava/nio/ByteBuffer;",
                false));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));

        wrapper.instructions.add(preparedFallback);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "useCarrierForOriginalFallback",
                "(Ljava/awt/image/BufferedImage;)Z",
                false));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, decodeOriginal));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, owner, ORIGINAL_CONVERT, CONVERT_DESCRIPTOR, false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.GOTO, converted));

        wrapper.instructions.add(decodeOriginal);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "originalPath",
                "(Ljava/awt/image/BufferedImage;)Ljava/lang/String;",
                false));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, owner, ORIGINAL_DECODE, DECODE_DESCRIPTOR, false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, owner, ORIGINAL_CONVERT, CONVERT_DESCRIPTOR, false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5));

        wrapper.instructions.add(converted);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "observeOriginalFallback",
                "(Ljava/awt/image/BufferedImage;Ljava/nio/ByteBuffer;)V",
                false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));

        wrapper.instructions.add(ordinary);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, owner, ORIGINAL_CONVERT, CONVERT_DESCRIPTOR, false));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
        return wrapper;
    }

    private static void addDimensionSetter(MethodNode wrapper, String method, String preparedAccessor) {
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                PREPARED_PIXEL,
                preparedAccessor,
                "()I",
                false));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                TEXTURE_OBJECT,
                method,
                DIMENSION_SETTER_DESCRIPTOR,
                false));
    }

    private static MethodNode cleanupWrapper(
            String owner, MethodMetadata metadata, String methodName) {
        MethodNode wrapper = method(metadata, methodName, CLEANUP_DESCRIPTOR);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));

        wrapper.instructions.add(start);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, owner, ORIGINAL_CLEANUP, CLEANUP_DESCRIPTOR, false));
        wrapper.instructions.add(end);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "release", "(Ljava/nio/ByteBuffer;)V", false));
        wrapper.instructions.add(new InsnNode(Opcodes.RETURN));

        wrapper.instructions.add(handler);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "release", "(Ljava/nio/ByteBuffer;)V", false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new InsnNode(Opcodes.ATHROW));
        return wrapper;
    }

    private static DimensionSetters dimensionSetters(MethodNode convert) {
        List<String> setters = new ArrayList<>();
        for (AbstractInsnNode instruction = convert.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && TEXTURE_OBJECT.equals(call.owner)
                    && DIMENSION_SETTER_DESCRIPTOR.equals(call.desc)) {
                setters.add(call.name);
                if (setters.size() > 2) {
                    return null;
                }
            }
        }
        if (setters.size() != 2 || setters.get(0).equals(setters.get(1))) {
            return null;
        }
        // The exact installed converter invokes its height setter first and width setter second.
        return new DimensionSetters(setters.get(1), setters.get(0));
    }

    private static List<MethodNode> directConvertCallers(
            ClassNode owner, MethodNode convert, String convertName) {
        List<MethodNode> callers = new ArrayList<>();
        for (MethodNode method : owner.methods) {
            if (method == convert) {
                continue;
            }
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && owner.name.equals(call.owner)
                        && convertName.equals(call.name)
                        && CONVERT_DESCRIPTOR.equals(call.desc)) {
                    callers.add(method);
                    break;
                }
            }
        }
        return List.copyOf(callers);
    }

    private static void addExceptionalRelease(MethodNode method) {
        if (method.instructions.getFirst() == null) {
            return;
        }
        int errorLocal = method.maxLocals;
        method.maxLocals++;
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.insertBefore(method.instructions.getFirst(), start);
        method.instructions.add(end);
        method.instructions.add(handler);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, errorLocal));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "releaseCurrentThreadBuffer",
                "()V",
                false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, errorLocal));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
    }

    private static MethodMetadata rename(
            String owner,
            MethodNode original,
            String replacement,
            String sourceName,
            String descriptor) {
        MethodMetadata metadata = new MethodMetadata(
                original.access,
                original.signature,
                original.exceptions == null ? List.of() : List.copyOf(original.exceptions));
        original.name = replacement;
        original.access = (original.access
                & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_BRIDGE
                | Opcodes.ACC_VARARGS | Opcodes.ACC_STRICT))
                | Opcodes.ACC_PRIVATE
                | Opcodes.ACC_SYNTHETIC;
        rewriteSelfCalls(owner, original, sourceName, replacement, descriptor);
        return metadata;
    }

    private static MethodNode directDecodeClone(MethodNode decode) {
        MethodNode clone = new MethodNode(
                Opcodes.ASM9,
                decode.access,
                decode.name,
                decode.desc,
                decode.signature,
                decode.exceptions == null ? null : decode.exceptions.toArray(String[]::new));
        decode.accept(clone);
        PreloaderHandoff handoff = preloaderHandoff(clone);
        if (handoff == null) {
            return null;
        }
        AbstractInsnNode afterReturn = handoff.returnImage().getNext();
        for (AbstractInsnNode instruction = handoff.argumentLoad(); instruction != afterReturn; ) {
            AbstractInsnNode next = instruction.getNext();
            clone.instructions.remove(instruction);
            instruction = next;
        }
        clone.name = ORIGINAL_DECODE;
        clone.access = (clone.access
                & (Opcodes.ACC_FINAL | Opcodes.ACC_BRIDGE | Opcodes.ACC_VARARGS | Opcodes.ACC_STRICT))
                | Opcodes.ACC_PRIVATE
                | Opcodes.ACC_SYNTHETIC;
        return clone;
    }

    private static void injectPreparedLookup(MethodNode decode, AbstractInsnNode directDecode) {
        LabelNode continueOriginal = new LabelNode();
        InsnList lookup = new InsnList();
        lookup.add(new VarInsnNode(Opcodes.ALOAD, 1));
        lookup.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "load", DECODE_DESCRIPTOR, false));
        lookup.add(new InsnNode(Opcodes.DUP));
        lookup.add(new JumpInsnNode(Opcodes.IFNULL, continueOriginal));
        lookup.add(new InsnNode(Opcodes.ARETURN));
        lookup.add(continueOriginal);
        lookup.add(new InsnNode(Opcodes.POP));
        decode.instructions.insertBefore(directDecode, lookup);
    }

    private static PreloaderHandoff preloaderHandoff(MethodNode method) {
        PreloaderHandoff found = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !PRELOADER.equals(call.owner)
                    || (!PRELOADER_METHOD.equals(call.name)
                            && !WINDOWS_PRELOADER_METHOD.equals(call.name))
                    || !DECODE_DESCRIPTOR.equals(call.desc)) {
                continue;
            }
            AbstractInsnNode argumentLoad = previousOpcode(call);
            AbstractInsnNode store = nextOpcode(call);
            AbstractInsnNode loadForBranch = nextOpcode(store);
            AbstractInsnNode branch = nextOpcode(loadForBranch);
            AbstractInsnNode loadForReturn = nextOpcode(branch);
            AbstractInsnNode returnImage = nextOpcode(loadForReturn);
            if (!(argumentLoad instanceof VarInsnNode argument)
                    || argumentLoad.getOpcode() != Opcodes.ALOAD
                    || argument.var != 1
                    || !(store instanceof VarInsnNode stored)
                    || store.getOpcode() != Opcodes.ASTORE
                    || !(loadForBranch instanceof VarInsnNode branchLoad)
                    || loadForBranch.getOpcode() != Opcodes.ALOAD
                    || branchLoad.var != stored.var
                    || !(branch instanceof JumpInsnNode jump)
                    || branch.getOpcode() != Opcodes.IFNULL
                    || !(loadForReturn instanceof VarInsnNode returnLoad)
                    || loadForReturn.getOpcode() != Opcodes.ALOAD
                    || returnLoad.var != stored.var
                    || returnImage == null
                    || returnImage.getOpcode() != Opcodes.ARETURN) {
                return null;
            }
            AbstractInsnNode directDecode = nextOpcode(jump.label);
            if (directDecode == null || found != null) {
                return null;
            }
            found = new PreloaderHandoff(argumentLoad, returnImage, directDecode);
        }
        return found;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
        while (previous instanceof LabelNode || previous instanceof FrameNode || previous instanceof LineNumberNode) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction == null ? null : instruction.getNext();
        while (next instanceof LabelNode || next instanceof FrameNode || next instanceof LineNumberNode) {
            next = next.getNext();
        }
        return next;
    }

    private static MethodNode method(MethodMetadata metadata, String name, String descriptor) {
        return new MethodNode(
                Opcodes.ASM9,
                metadata.access(),
                name,
                descriptor,
                metadata.signature(),
                metadata.exceptions().toArray(String[]::new));
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) {
                    return null;
                }
                found = method;
            }
        }
        return found;
    }

    private static MethodNode uniqueSupportedMethod(
            ClassNode owner, String firstName, String secondName, String descriptor) {
        MethodNode first = uniqueMethod(owner, firstName, descriptor);
        MethodNode second = uniqueMethod(owner, secondName, descriptor);
        return first == null ? second : second == null ? first : null;
    }

    private static boolean hasSupportedMethod(
            ClassSignature signature, String firstName, String secondName, String descriptor) {
        return signature.hasMethod(firstName, descriptor) || signature.hasMethod(secondName, descriptor);
    }

    private static boolean hasMethod(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().anyMatch(method -> name.equals(method.name) && descriptor.equals(method.desc));
    }

    private static boolean eligibleInstance(MethodNode method) {
        return method != null
                && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC)) == 0;
    }

    private static boolean eligibleStatic(MethodNode method) {
        return method != null
                && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0
                && (method.access & Opcodes.ACC_STATIC) != 0;
    }

    private static void rewriteSelfCalls(
            String owner,
            MethodNode method,
            String sourceName,
            String replacement,
            String descriptor) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && sourceName.equals(call.name)
                    && descriptor.equals(call.desc)) {
                call.name = replacement;
                call.setOpcode((method.access & Opcodes.ACC_STATIC) != 0
                        ? Opcodes.INVOKESTATIC
                        : Opcodes.INVOKESPECIAL);
            }
        }
    }

    private record PreloaderHandoff(
            AbstractInsnNode argumentLoad,
            AbstractInsnNode returnImage,
            AbstractInsnNode directDecode) {
    }

    private record DimensionSetters(String widthMethod, String heightMethod) {
    }

    private record MethodMetadata(int access, String signature, List<String> exceptions) {
        private MethodMetadata {
            exceptions = List.copyOf(exceptions);
        }
    }

}
