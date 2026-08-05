package dev.starsector.preflight.agent;

import java.io.IOException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Defers GraphicsLib cached normal-map uploads until its existing gameplay preloader needs them. */
final class GraphicsLibLazyNormalPlan {
    static final String BASE_SHA256 =
            "88745c0a99c38732a1b8ad3660be18daa086dbd912989107f70b526731bc8795";
    static final String OPTIMIZED_SHA256 =
            "12570bba67842564652d01f31fed550f62f500e91eac29145ac0fae189fa4c6a";

    private static final String TEXTURE_DATA = "org/dark/shaders/util/TextureData";
    private static final String TEXTURE_ENTRY = "org/dark/shaders/util/TextureEntry";
    private static final String SPRITE_API = "com/fs/starfarer/api/graphics/SpriteAPI";
    private static final String SETTINGS = "org/dark/shaders/util/GraphicsLibSettings";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/GraphicsLibNormalCacheRuntime";
    private static final String AUTO_GEN_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;Lorg/dark/shaders/util/"
                    + "TextureData$ObjectType;I)Lcom/fs/starfarer/api/graphics/SpriteAPI;";
    private static final String MAP_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;Lorg/dark/shaders/util/"
                    + "TextureData$ObjectType;IZZ)V";

    private GraphicsLibLazyNormalPlan() {
    }

    static byte[] transform(byte[] base) throws IOException {
        ClassSignature signature = ClassSignature.parse(base);
        if (!TEXTURE_DATA.equals(signature.internalName())
                || !BASE_SHA256.equals(signature.sha256())) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(base).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode autoGen = exactMethod(owner, "autoGenNormalMap", AUTO_GEN_DESCRIPTOR);
        MethodNode map = exactMethod(owner, "mapSpriteToMNSWithAutoGen", MAP_DESCRIPTOR);
        if (autoGen == null || map == null
                || !injectValidatedCacheHit(autoGen)
                || !injectLazyUnload(map)) {
            return null;
        }
        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static boolean injectValidatedCacheHit(MethodNode method) {
        JumpInsnNode invalidated = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETSTATIC
                    || !TEXTURE_DATA.equals(field.owner)
                    || !"invalidateCache".equals(field.name)
                    || !"Z".equals(field.desc)) {
                continue;
            }
            AbstractInsnNode next = nextOpcode(field);
            if (invalidated != null || !(next instanceof JumpInsnNode jump)
                    || jump.getOpcode() != Opcodes.IFNE) {
                return false;
            }
            invalidated = jump;
        }
        if (invalidated == null) {
            return false;
        }
        AbstractInsnNode original = nextOpcode(invalidated);
        if (original == null) {
            return false;
        }
        LabelNode miss = new LabelNode();
        LabelNode resume = new LabelNode();
        InsnList lazy = new InsnList();
        lazy.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SETTINGS,
                "preloadAllMaps", "()Z", false));
        lazy.add(new JumpInsnNode(Opcodes.IFNE, resume));
        lazy.add(new VarInsnNode(Opcodes.ALOAD, 5));
        lazy.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "lazySprite", "(Ljava/lang/String;)Ljava/lang/Object;", false));
        lazy.add(new InsnNode(Opcodes.DUP));
        lazy.add(new JumpInsnNode(Opcodes.IFNULL, miss));
        lazy.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, SPRITE_API));
        lazy.add(new InsnNode(Opcodes.ARETURN));
        lazy.add(miss);
        lazy.add(new InsnNode(Opcodes.POP));
        lazy.add(resume);
        method.instructions.insertBefore(original, lazy);
        return true;
    }

    private static boolean injectLazyUnload(MethodNode method) {
        MethodInsnNode textureId = null;
        FieldInsnNode vramUpdate = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && SPRITE_API.equals(call.owner)
                    && "getTextureId".equals(call.name)
                    && "()I".equals(call.desc)) {
                if (textureId != null) {
                    return false;
                }
                textureId = call;
            }
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTSTATIC
                    && TEXTURE_DATA.equals(field.owner)
                    && "vramUsageBytes".equals(field.name)
                    && "J".equals(field.desc)) {
                vramUpdate = field;
            }
        }
        if (textureId == null || vramUpdate == null) {
            return false;
        }
        AbstractInsnNode start = previousOpcode(textureId);
        if (!(start instanceof FieldInsnNode sprite)
                || sprite.getOpcode() != Opcodes.GETFIELD
                || !TEXTURE_ENTRY.equals(sprite.owner)
                || !"sprite".equals(sprite.name)) {
            return false;
        }
        start = previousOpcode(start);
        if (!(start instanceof VarInsnNode loadEntry)
                || loadEntry.getOpcode() != Opcodes.ALOAD
                || loadEntry.var != 6) {
            return false;
        }
        AbstractInsnNode resumeAt = nextOpcode(vramUpdate);
        if (!(resumeAt instanceof FieldInsnNode nextField)
                || nextField.getOpcode() != Opcodes.GETSTATIC
                || !TEXTURE_DATA.equals(nextField.owner)
                || !"normalKeyToEntry".equals(nextField.name)) {
            return false;
        }
        LabelNode ordinary = new LabelNode();
        LabelNode resume = new LabelNode();
        method.instructions.insertBefore(resumeAt, resume);
        InsnList lazy = new InsnList();
        lazy.add(new VarInsnNode(Opcodes.ALOAD, 6));
        lazy.add(new FieldInsnNode(Opcodes.GETFIELD, TEXTURE_ENTRY, "sprite",
                "Lcom/fs/starfarer/api/graphics/SpriteAPI;"));
        lazy.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "isLazySprite", "(Ljava/lang/Object;)Z", false));
        lazy.add(new JumpInsnNode(Opcodes.IFEQ, ordinary));
        lazy.add(new VarInsnNode(Opcodes.ALOAD, 6));
        lazy.add(new InsnNode(Opcodes.ACONST_NULL));
        lazy.add(new FieldInsnNode(Opcodes.PUTFIELD, TEXTURE_ENTRY, "sprite",
                "Lcom/fs/starfarer/api/graphics/SpriteAPI;"));
        lazy.add(new VarInsnNode(Opcodes.ALOAD, 6));
        lazy.add(new InsnNode(Opcodes.ICONST_0));
        lazy.add(new FieldInsnNode(Opcodes.PUTFIELD, TEXTURE_ENTRY, "loaded", "Z"));
        lazy.add(new VarInsnNode(Opcodes.ALOAD, 6));
        lazy.add(new InsnNode(Opcodes.LCONST_0));
        lazy.add(new FieldInsnNode(Opcodes.PUTFIELD, TEXTURE_ENTRY, "vramSize", "J"));
        lazy.add(new JumpInsnNode(Opcodes.GOTO, resume));
        lazy.add(ordinary);
        method.instructions.insertBefore(start, lazy);
        return true;
    }

    private static MethodNode exactMethod(ClassNode owner, String name, String descriptor) {
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

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }
}
