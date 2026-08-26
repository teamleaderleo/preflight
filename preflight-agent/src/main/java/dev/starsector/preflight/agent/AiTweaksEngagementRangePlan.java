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

/** Snapshots fixed weapon geometry and boxes fixed ranges once inside one target selection. */
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
    private static final String LOCATION_GETTER = "getLocation-impl";
    private static final String LOCATION_DESCRIPTOR = "(Lcom/fs/starfarer/api/combat/WeaponAPI;)"
            + "Lorg/lwjgl/util/vector/Vector2f;";
    private static final String WEAPON_FIELD = "weapon";
    private static final String WEAPON_DESCRIPTOR = "Lcom/fs/starfarer/api/combat/WeaponAPI;";
    private static final String CACHE_FIELD = "preflight$engagementRange";
    private static final String BOXED_CACHE_FIELD = "preflight$engagementRangeBoxed";
    private static final String TARGET_SEARCH_FIELD = "targetSearchRange";
    private static final String TARGET_SEARCH_BOXED_FIELD = "preflight$targetSearchRangeBoxed";
    private static final String LOCATION_CACHE_FIELD = "preflight$weaponLocation";
    private static final String LOCATION_DESCRIPTOR_VALUE = "Lorg/lwjgl/util/vector/Vector2f;";
    private static final String FLOAT = "java/lang/Float";
    private static final String BOX_DESCRIPTOR = "(F)Ljava/lang/Float;";
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
        if (owner.fields.stream().anyMatch(field -> field.name.startsWith("preflight$"))) return null;
        MethodNode constructor = unique(owner, CONSTRUCTOR, CONSTRUCTOR_DESCRIPTOR);
        if (constructor == null || field(owner, WEAPON_FIELD, WEAPON_DESCRIPTOR) == null
                || field(owner, TARGET_SEARCH_FIELD, "F") == null) return null;

        List<CallSite> sites = new ArrayList<>();
        List<CallSite> locationSites = new ArrayList<>();
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
                if (instruction instanceof MethodInsnNode call
                        && WEAPON_HANDLE.equals(call.owner)
                        && LOCATION_GETTER.equals(call.name)
                        && LOCATION_DESCRIPTOR.equals(call.desc)) {
                    AbstractInsnNode previous = previousCode(call);
                    if (!(previous instanceof FieldInsnNode weapon)
                            || weapon.getOpcode() != Opcodes.GETFIELD
                            || !TARGET_CLASS.equals(weapon.owner)
                            || !WEAPON_FIELD.equals(weapon.name)
                            || !WEAPON_DESCRIPTOR.equals(weapon.desc)) {
                        return null;
                    }
                    locationSites.add(new CallSite(method, call));
                }
            }
        }
        List<CallSite> constructorSites = sites.stream()
                .filter(site -> site.method() == constructor).toList();
        List<CallSite> boxedEngagementSites = sites.stream()
                .filter(site -> site.method() != constructor)
                .filter(site -> isBox(nextCode(site.call())))
                .toList();
        List<FieldBoxSite> boxedSearchSites = new ArrayList<>();
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETFIELD
                        && TARGET_CLASS.equals(field.owner)
                        && TARGET_SEARCH_FIELD.equals(field.name)
                        && "F".equals(field.desc)
                        && isBox(nextCode(field))) {
                    boxedSearchSites.add(new FieldBoxSite(
                            method, field, (MethodInsnNode) nextCode(field)));
                }
            }
        }
        if (sites.size() != 5 || locationSites.size() != 6 || constructorSites.size() != 1
                || boxedEngagementSites.size() != 2 || boxedSearchSites.size() != 1
                || sites.stream().filter(site -> site.method() != constructor)
                        .anyMatch(site -> (site.method().access & Opcodes.ACC_STATIC) != 0)) {
            return null;
        }

        int cacheAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
        owner.fields.add(new FieldNode(cacheAccess, CACHE_FIELD, "F", null, null));
        owner.fields.add(new FieldNode(
                cacheAccess, BOXED_CACHE_FIELD, "Ljava/lang/Float;", null, null));
        owner.fields.add(new FieldNode(
                cacheAccess, TARGET_SEARCH_BOXED_FIELD, "Ljava/lang/Float;", null, null));
        owner.fields.add(new FieldNode(
                cacheAccess, LOCATION_CACHE_FIELD, LOCATION_DESCRIPTOR_VALUE, null, null));
        MethodInsnNode capture = constructorSites.get(0).call();
        InsnList save = new InsnList();
        save.add(new InsnNode(Opcodes.DUP));
        save.add(new VarInsnNode(Opcodes.ALOAD, 0));
        save.add(new InsnNode(Opcodes.SWAP));
        save.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_CLASS, CACHE_FIELD, "F"));
        save.add(new InsnNode(Opcodes.DUP));
        save.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, FLOAT, "valueOf", BOX_DESCRIPTOR, false));
        save.add(new VarInsnNode(Opcodes.ALOAD, 0));
        save.add(new InsnNode(Opcodes.SWAP));
        save.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_CLASS,
                BOXED_CACHE_FIELD, "Ljava/lang/Float;"));
        save.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "snapshot", "()V", false));
        constructor.instructions.insert(capture, save);

        FieldInsnNode targetSearchAssignment = uniqueField(
                constructor, Opcodes.PUTFIELD, TARGET_SEARCH_FIELD, "F");
        if (targetSearchAssignment == null) return null;
        InsnList saveSearch = new InsnList();
        saveSearch.add(new VarInsnNode(Opcodes.ALOAD, 0));
        saveSearch.add(new VarInsnNode(Opcodes.ALOAD, 0));
        saveSearch.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, TARGET_SEARCH_FIELD, "F"));
        saveSearch.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, FLOAT, "valueOf", BOX_DESCRIPTOR, false));
        saveSearch.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_CLASS,
                TARGET_SEARCH_BOXED_FIELD, "Ljava/lang/Float;"));
        saveSearch.add(new VarInsnNode(Opcodes.ALOAD, 0));
        saveSearch.add(new VarInsnNode(Opcodes.ALOAD, 0));
        saveSearch.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, WEAPON_FIELD, WEAPON_DESCRIPTOR));
        saveSearch.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, WEAPON_HANDLE, LOCATION_GETTER,
                LOCATION_DESCRIPTOR, false));
        saveSearch.add(new FieldInsnNode(Opcodes.PUTFIELD, TARGET_CLASS,
                LOCATION_CACHE_FIELD, LOCATION_DESCRIPTOR_VALUE));
        constructor.instructions.insert(targetSearchAssignment, saveSearch);

        for (CallSite site : sites) {
            if (site.call() == capture) continue;
            InsnList cached = new InsnList();
            cached.add(new InsnNode(Opcodes.POP));
            cached.add(new VarInsnNode(Opcodes.ALOAD, 0));
            boolean boxed = boxedEngagementSites.contains(site);
            MethodInsnNode originalBox = boxed ? (MethodInsnNode) nextCode(site.call()) : null;
            cached.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_CLASS,
                    boxed ? BOXED_CACHE_FIELD : CACHE_FIELD,
                    boxed ? "Ljava/lang/Float;" : "F"));
            site.method().instructions.insertBefore(site.call(), cached);
            site.method().instructions.remove(site.call());
            if (boxed) site.method().instructions.remove(originalBox);
        }
        for (FieldBoxSite site : boxedSearchSites) {
            site.field().name = TARGET_SEARCH_BOXED_FIELD;
            site.field().desc = "Ljava/lang/Float;";
            site.method().instructions.remove(site.box());
        }
        for (CallSite site : locationSites) {
            FieldInsnNode weapon = (FieldInsnNode) previousCode(site.call());
            weapon.name = LOCATION_CACHE_FIELD;
            weapon.desc = LOCATION_DESCRIPTOR_VALUE;
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

    private static FieldInsnNode uniqueField(
            MethodNode method, int opcode, String name, String descriptor) {
        FieldInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == opcode
                    && TARGET_CLASS.equals(field.owner)
                    && name.equals(field.name) && descriptor.equals(field.desc)) {
                if (found != null) return null;
                found = field;
            }
        }
        return found;
    }

    private static boolean isBox(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC
                && FLOAT.equals(call.owner)
                && "valueOf".equals(call.name)
                && BOX_DESCRIPTOR.equals(call.desc);
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private record CallSite(MethodNode method, MethodInsnNode call) {
    }

    private record FieldBoxSite(
            MethodNode method, FieldInsnNode field, MethodInsnNode box) {
    }
}
