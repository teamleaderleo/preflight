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
    private static final String REPLACEMENT_SHA256 =
            "88745c0a99c38732a1b8ad3660be18daa086dbd912989107f70b526731bc8795";
    private static final String INNER = "autoGenMissingNormalMapsInner";
    private static final String MAP = "mapSpriteToMNSWithAutoGen";

    private StartupGraphicsTextureBreakdownPlan() {
    }

    static byte[] transform(byte[] replacement) throws IOException {
        ClassSignature signature = ClassSignature.parse(replacement);
        if (!TEXTURE_DATA.equals(signature.internalName())
                || !REPLACEMENT_SHA256.equals(signature.sha256())) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(replacement).accept(owner, ClassReader.EXPAND_FRAMES);
        boolean changed = false;
        for (MethodNode method : owner.methods) {
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
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)) {
                continue;
            }
            String label = label(method, call);
            if (label != null) {
                sites.add(new Site(call, label));
            }
        }
        return sites;
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

    private record Site(MethodInsnNode call, String label) {
    }
}
