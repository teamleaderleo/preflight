package dev.starsector.preflight.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Opt-in attribution inside Preflight's exact GraphicsLib compact replay replacement. */
final class StartupGraphicsTextureBreakdownPlan {
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/StartupPhaseRuntime";
    private static final String TEXTURE_DATA = "org/dark/shaders/util/TextureData";
    private static final String INNER = "autoGenMissingNormalMapsInner";
    private static final String MAP = "mapSpriteToMNSWithAutoGen";
    private static final String NORMAL_MAP_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;Lorg/dark/shaders/util/"
                    + "TextureData$ObjectType;I)Lcom/fs/starfarer/api/graphics/SpriteAPI;";

    private StartupGraphicsTextureBreakdownPlan() {
    }

    static byte[] transform(byte[] replacement) throws IOException {
        ClassSignature signature = ClassSignature.parse(replacement);
        if (!TEXTURE_DATA.equals(signature.internalName())
                || !(GraphicsLibLazyNormalPlan.BASE_SHA256.equals(signature.sha256())
                || GraphicsLibLazyNormalPlan.OPTIMIZED_SHA256.equals(signature.sha256()))) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(replacement).accept(owner, ClassReader.EXPAND_FRAMES);
        boolean changed = false;
        for (MethodNode method : owner.methods) {
            changed |= instrumentNormalMapPath(method);
            List<Site> sites = sites(method);
            if (sites.isEmpty()) {
                continue;
            }
            int tokenLocal = method.maxLocals;
            method.maxLocals += 2;
            for (Site site : sites) {
                InsnList before = new InsnList();
                before.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, RUNTIME, "hotCallStart", "()J", false));
                before.add(new VarInsnNode(Opcodes.LSTORE, tokenLocal));
                method.instructions.insertBefore(site.call(), before);
                InsnList after = new InsnList();
                after.add(new LdcInsnNode(site.label()));
                after.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
                after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                        "hotCallEnd", "(Ljava/lang/String;J)V", false));
                method.instructions.insert(site.call(), after);
                changed = true;
            }
        }
        if (!changed) {
            return null;
        }
        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static List<Site> sites(MethodNode method) {
        List<Site> sites = new ArrayList<>();
        int normalGetSprite = 0;
        int normalLoadTexture = 0;
        int normalForceMipmaps = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            String label = label(method, call);
            if ("autoGenNormalMap".equals(method.name)
                    && "com/fs/starfarer/api/SettingsAPI".equals(call.owner)) {
                if ("getSprite".equals(call.name)) {
                    label = switch (++normalGetSprite) {
                        case 1 -> "gfx.normal.cachedSpriteProbe";
                        case 2 -> "gfx.normal.cachedSpriteAfterLoad";
                        case 3 -> "gfx.normal.sourceSprite";
                        case 4 -> "gfx.normal.generatedSpriteAfterWrite";
                        default -> "gfx.normal.extraGetSprite";
                    };
                } else if ("loadTexture".equals(call.name)) {
                    label = ++normalLoadTexture == 1
                            ? "gfx.normal.loadCachedTexture" : "gfx.normal.loadGeneratedTexture";
                } else if ("forceMipmapsFor".equals(call.name)) {
                    label = ++normalForceMipmaps == 1
                            ? "gfx.normal.forceCachedMipmaps"
                            : "gfx.normal.forceGeneratedMipmaps";
                }
            }
            if (label != null) {
                sites.add(new Site(call, label));
            }
        }
        return sites;
    }

    private static boolean instrumentNormalMapPath(MethodNode method) {
        if (!"autoGenNormalMap".equals(method.name)
                || !NORMAL_MAP_DESCRIPTOR.equals(method.desc)) {
            return false;
        }
        VarInsnNode store = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && TEXTURE_DATA.equals(call.owner)
                    && "filePathForCache".equals(call.name)) {
                if (store != null) {
                    return false;
                }
                AbstractInsnNode next = nextOpcode(call);
                if (!(next instanceof VarInsnNode variable)
                        || variable.getOpcode() != Opcodes.ASTORE) {
                    return false;
                }
                store = variable;
            }
        }
        if (store == null) {
            return false;
        }
        InsnList record = new InsnList();
        record.add(new LdcInsnNode("gfx.normal.cachePath"));
        record.add(new VarInsnNode(Opcodes.ALOAD, store.var));
        record.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "hotPath", "(Ljava/lang/String;Ljava/lang/String;)V", false));
        method.instructions.insert(store, record);
        return true;
    }

    private static String label(MethodNode method, MethodInsnNode call) {
        if ("autoGenMissingNormalMaps".equals(method.name)) {
            if (TEXTURE_DATA.equals(call.owner) && INNER.equals(call.name)) {
                AbstractInsnNode previous = previousOpcode(call);
                return previous != null && previous.getOpcode() == Opcodes.ICONST_1
                        ? "gfx.autoGen.innerTrue" : "gfx.autoGen.innerFalse";
            }
            if (TEXTURE_DATA.equals(call.owner) && MAP.equals(call.name)) {
                return "gfx.autoGen.compactReplay";
            }
        }
        if (INNER.equals(method.name)) {
            if (TEXTURE_DATA.equals(call.owner) && "loadTraversalJson".equals(call.name)) {
                return "gfx.autoGen.loadTraversalJson";
            }
            if (TEXTURE_DATA.equals(call.owner) && MAP.equals(call.name)) {
                return "gfx.autoGen.linkMaps";
            }
        }
        if (MAP.equals(method.name)) {
            if (TEXTURE_DATA.equals(call.owner)
                    && "getTextureDataWithAutoGen".equals(call.name)) {
                return "gfx.map.textureLookup";
            }
            if (TEXTURE_DATA.equals(call.owner) && "autoGenNormalMap".equals(call.name)) {
                return "gfx.map.generateOrLoadNormal";
            }
            if (TEXTURE_DATA.equals(call.owner) && "isAlwaysPreload".equals(call.name)) {
                return "gfx.map.preloadPolicy";
            }
            if ("com/fs/starfarer/api/SettingsAPI".equals(call.owner)
                    && "unloadTexture".equals(call.name)) {
                return "gfx.map.unloadTexture";
            }
            if ("org/lwjgl/opengl/GL11".equals(call.owner)
                    && "glDeleteTextures".equals(call.name)) {
                return "gfx.map.deleteTexture";
            }
        }
        return null;
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

    private record Site(MethodInsnNode call, String label) {
    }
}
