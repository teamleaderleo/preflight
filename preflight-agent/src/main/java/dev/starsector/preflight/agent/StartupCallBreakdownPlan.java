package dev.starsector.preflight.agent;

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

/** Opt-in call-site attribution inside exact AshLib and GraphicsLib startup classes. */
final class StartupCallBreakdownPlan {
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/StartupPhaseRuntime";

    private static final List<Probe> PROBES = List.of(
            new Probe(
                    "ashlib/data/plugins/repositories/ShipRenderInfoRepo",
                    "5955d8f27dba81580e2648bbc0a7a16a9924bcd1734baf7937ab1d3417e6507f",
                    List.of(
                            call("populateRenderInfoRepo", "()V",
                                    "ashlib/data/plugins/repositories/ShipRenderInfoRepo",
                                    "populateShip",
                                    "(Lcom/fs/starfarer/api/combat/ShipHullSpecAPI;)V",
                                    "ash.populateShip"),
                            call("populateShip", "(Lcom/fs/starfarer/api/combat/ShipHullSpecAPI;)V",
                                    "ashlib/data/plugins/misc/AshMisc", "getVaraint",
                                    "(Lcom/fs/starfarer/api/combat/ShipHullSpecAPI;)Ljava/lang/String;",
                                    "ash.variantLookup"),
                            call("populateShip", "(Lcom/fs/starfarer/api/combat/ShipHullSpecAPI;)V",
                                    "ashlib/data/plugins/models/ShipRenderInfo",
                                    "getModuleSlotsFromVariantFile", "(Ljava/lang/String;)V",
                                    "ash.moduleSlotsFromVariant"),
                            call("populateShip", "(Lcom/fs/starfarer/api/combat/ShipHullSpecAPI;)V",
                                    "ashlib/data/plugins/models/ShipRenderInfo",
                                    "populateSlotShipHullsMap", "()V", "ash.slotShipHulls"),
                            call("populateShip", "(Lcom/fs/starfarer/api/combat/ShipHullSpecAPI;)V",
                                    "ashlib/data/plugins/models/ShipRenderInfo",
                                    "populateModuleList", "(Ljava/lang/String;)V",
                                    "ash.moduleList"))),
            new Probe(
                    "ashlib/data/plugins/models/ShipRenderInfo",
                    "bb8d74bfb775f63ba79aa802c7e67158b5eea80c2d3057f9fd40350fd99e1aed",
                    List.of(
                            anyCall("com/fs/starfarer/api/SettingsAPI", "loadJSON",
                                    "ash.renderInfo.loadJSON"))),
            new Probe(
                    "org/dark/shaders/ShaderModPlugin",
                    "5863b38d7ea73ed65fb8d214e525daed0318f4563b92a15d22e0981cec275981",
                    List.of(
                            topCall("org/dark/shaders/util/ShaderLib", "init", "gfx.shaderInit"),
                            topCall("org/dark/graphics/util/ShipColors", "init", "gfx.shipColors"),
                            topCall("org/dark/shaders/light/LightData",
                                    "readLightDataCSVNoOverwrite", "gfx.lightCsv"),
                            topCall("org/dark/shaders/util/TextureData",
                                    "readTextureDataCSVNoOverwrite", "gfx.textureCsv"),
                            topCall("org/dark/shaders/util/GraphicsLibSettings",
                                    "loadWave", "gfx.loadWave"),
                            topCall("org/dark/shaders/util/GraphicsLibSettings",
                                    "loadSmallRipple", "gfx.loadSmallRipple"),
                            topCall("org/dark/shaders/util/GraphicsLibSettings",
                                    "loadLargeRipple", "gfx.loadLargeRipple"),
                            topCall("org/dark/shaders/util/GraphicsLibSettings",
                                    "loadThreat", "gfx.loadThreat"),
                            topCall("org/dark/shaders/util/TextureData",
                                    "autoGenMissingNormalMaps", "gfx.autoGenMissingNormals"))));

    private StartupCallBreakdownPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        Probe probe = probe(signature);
        if (probe == null) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        boolean changed = false;
        for (MethodNode method : owner.methods) {
            List<Call> matches = new ArrayList<>();
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode invoked)
                        || invoked.getOpcode() == Opcodes.INVOKESPECIAL) {
                    continue;
                }
                for (Call call : probe.calls()) {
                    if (call.matches(method, invoked)) {
                        matches.add(call.withInstruction(invoked));
                        break;
                    }
                }
            }
            if (matches.isEmpty()) {
                continue;
            }
            int tokenLocal = method.maxLocals;
            method.maxLocals += 2;
            for (Call match : matches) {
                MethodInsnNode invoked = match.instruction();
                InsnList before = new InsnList();
                before.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, RUNTIME, "hotCallStart", "()J", false));
                before.add(new VarInsnNode(Opcodes.LSTORE, tokenLocal));
                method.instructions.insertBefore(invoked, before);
                InsnList after = new InsnList();
                after.add(new LdcInsnNode(match.label()));
                after.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
                after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                        "hotCallEnd", "(Ljava/lang/String;J)V", false));
                method.instructions.insert(invoked, after);
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

    static Probe probe(ClassSignature signature) {
        for (Probe probe : PROBES) {
            if (probe.className().equals(signature.internalName())
                    && probe.sha256().equals(signature.sha256())) {
                return probe;
            }
        }
        return null;
    }

    static List<Probe> probes() {
        return PROBES;
    }

    private static Call topCall(String owner, String name, String label) {
        return new Call("onApplicationLoad", "()V", owner, name, null, label, null);
    }

    private static Call anyCall(String owner, String name, String label) {
        return new Call(null, null, owner, name, null, label, null);
    }

    private static Call call(String caller, String callerDescriptor, String owner,
            String name, String descriptor, String label) {
        return new Call(caller, callerDescriptor, owner, name, descriptor, label, null);
    }

    record Probe(String className, String sha256, List<Call> calls) {
    }

    record Call(String caller, String callerDescriptor, String owner, String name,
            String descriptor, String label, MethodInsnNode instruction) {
        boolean matches(MethodNode method, MethodInsnNode invoked) {
            return (caller == null || caller.equals(method.name))
                    && (callerDescriptor == null || callerDescriptor.equals(method.desc))
                    && owner.equals(invoked.owner)
                    && name.equals(invoked.name)
                    && (descriptor == null || descriptor.equals(invoked.desc));
        }

        Call withInstruction(MethodInsnNode value) {
            return new Call(caller, callerDescriptor, owner, name, descriptor, label, value);
        }
    }
}
