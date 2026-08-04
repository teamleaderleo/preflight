package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Snapshots a derived weapon range once inside one short-lived AI Tweaks target selection. */
final class AiTweaksEngagementRangePlan {
    static final String TARGET_CLASS = "com/genir/aitweaks/core/shipai/autofire/SelectTarget";
    static final String ORIGINAL_SHA256 =
            "a87ebfe62a5a36d0b507cfc66822d3bcccda66c9c10f8c313b8a7b21924e97d5";
    static final String CONSTRUCTOR = "<init>";
    static final String CONSTRUCTOR_DESCRIPTOR = "(Lcom/fs/starfarer/api/combat/WeaponAPI;"
            + "Lcom/fs/starfarer/api/combat/CombatEntityAPI;"
            + "Lcom/fs/starfarer/api/combat/ShipAPI;"
            + "Lcom/genir/aitweaks/core/shipai/autofire/ballistics/BallisticParams;"
            + "Lcom/genir/aitweaks/core/shipai/global/TargetTracker;)V";

    private static final String WEAPON_HANDLE = "com/genir/aitweaks/core/handles/WeaponHandle";
    private static final String RANGE_GETTER = "getEngagementRange-impl";
    private static final String RANGE_DESCRIPTOR = "(Lcom/fs/starfarer/api/combat/WeaponAPI;)F";
    private static final String WEAPON_FIELD = "weapon";
    private static final String WEAPON_DESCRIPTOR = "Lcom/fs/starfarer/api/combat/WeaponAPI;";
    private static final String CACHE_FIELD = "preflight$engagementRange";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/AiTweaksEngagementRangeRuntime";

    private AiTweaksEngagementRangePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(CONSTRUCTOR, CONSTRUCTOR_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (owner.fields.stream().anyMatch(field -> CACHE_FIELD.equals(field.name))) return null;
        MethodNode constructor = unique(owner, CONSTRUCTOR, CONSTRUCTOR_DESCRIPTOR);
        if (constructor == null || field(owner, WEAPON_FIELD, WEAPON_DESCRIPTOR) == null) return null;

        List<CallSite> sites = new ArrayList<>();
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && WEAPON_HANDLE.equals(call.owner)
                        && RANGE_GETTER.equals(call.name)
                        && RANGE_DESCRIPTOR.equals(call.desc)) {
                    AbstractInsnNode previous = previousCode(call);
                    if (!(previous instanceof FieldInsnNode weapon)
                            || weapon.getOpcode() != Opcodes.GETFIELD
                            || !TARGET_CLASS.equals(weapon.owner)
                            || !WEAPON_FIELD.equals(weapon.name)
                            || !WEAPON_DESCRIPTOR.equals(weapon.desc)) {
                        return null;
                    }
                    sites.add(new CallSite(method, call));
                }
            }
        }
        List<CallSite> constructorSites = sites.stream()
                .filter(site -> site.method() == constructor).toList();
        if (sites.size() != 5 || constructorSites.size() != 1
                || sites.stream().filter(site -> site.method() != constructor)
                        .anyMatch(site -> (site.method().access & Opcodes.ACC_STATIC) != 0)) {
            return null;
        }

        owner.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                CACHE_FIELD, "F", null, null));
        MethodInsnNode capture = constructorSites.get(0).call();
        InsnList save = new InsnList();
        save.add(new InsnNode(Opcodes.DUP));
        save.add(new VarInsnNode(Opcodes.ALOAD, 0));
        save.add(new InsnNode(Opcodes.SWAP));
        save.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_CLASS, CACHE_FIELD, "F"));
        save.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "snapshot", "()V", false));
        constructor.instructions.insert(capture, save);

        for (CallSite site : sites) {
            if (site.call() == capture) continue;
            InsnList cached = new InsnList();
            cached.add(new InsnNode(Opcodes.POP));
            cached.add(new VarInsnNode(Opcodes.ALOAD, 0));
            cached.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_CLASS, CACHE_FIELD, "F"));
            site.method().instructions.insertBefore(site.call(), cached);
            site.method().instructions.remove(site.call());
        }

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        AiTweaksEngagementRangeRuntime.installed();
        return writer.toByteArray();
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static FieldNode field(ClassNode owner, String name, String descriptor) {
        return owner.fields.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
                .findFirst().orElse(null);
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private record CallSite(MethodNode method, MethodInsnNode call) {
    }
}
