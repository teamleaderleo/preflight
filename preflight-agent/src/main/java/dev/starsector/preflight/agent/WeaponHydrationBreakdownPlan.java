package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Samples numeric JSON conversion inside the exact reviewed weapon and projectile loaders. */
final class WeaponHydrationBreakdownPlan {
    static final String ORIGINAL_SHA256 =
            "c1e7a8a4c33d7ee7f714b05ac94dfa20745142d72ce868e954e8e6a04dc0544c";
    static final int WEAPON_SLOT = 0;
    static final int PROJECTILE_SLOT = 1;
    static final int PROJECTILE_JSON_OTHER_SLOT = 2;
    static final int PROJECTILE_SCHEMA_SLOT = 3;
    static final int PROJECTILE_SPEC_METHOD_SLOT = 4;
    static final int PROJECTILE_GAME_HELPER_SLOT = 5;
    private static final String JSON_OBJECT = "org/json/JSONObject";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/StartupPhaseRuntime";
    private static final int WEAPON_CALL_SITES = 24;
    private static final int PROJECTILE_CALL_SITES = 23;
    private static final int PROJECTILE_JSON_OTHER_CALL_SITES = 55;
    private static final int PROJECTILE_SCHEMA_CALL_SITES = 21;
    private static final int PROJECTILE_SPEC_METHOD_CALL_SITES = 78;
    private static final int PROJECTILE_GAME_HELPER_CALL_SITES = 11;

    private WeaponHydrationBreakdownPlan() {
    }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        if (!WeaponLoaderPhasePlan.TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())) {
            return false;
        }
        MethodNode weapon = uniqueMethod(owner,
                WeaponLoaderPhasePlan.LOAD_ONE_METHOD,
                WeaponLoaderPhasePlan.LOAD_ONE_DESCRIPTOR);
        MethodNode projectile = uniqueMethod(owner,
                ProjectileLoaderPhasePlan.LOAD_ONE_METHOD,
                ProjectileLoaderPhasePlan.LOAD_ONE_DESCRIPTOR);
        if (weapon == null || projectile == null || hasRuntimeCalls(weapon)
                || hasRuntimeCalls(projectile)) {
            return false;
        }
        List<MethodInsnNode> weaponCalls = numericCalls(weapon);
        List<MethodInsnNode> projectileCalls = numericCalls(projectile);
        List<MethodInsnNode> projectileJsonOtherCalls = matchingCalls(projectile,
                WeaponHydrationBreakdownPlan::isOtherJsonCall);
        List<MethodInsnNode> projectileSchemaCalls = matchingCalls(projectile,
                WeaponHydrationBreakdownPlan::isSchemaCall);
        List<MethodInsnNode> projectileSpecMethodCalls = matchingCalls(projectile,
                WeaponHydrationBreakdownPlan::isSpecMethodCall);
        List<MethodInsnNode> projectileGameHelperCalls = matchingCalls(projectile,
                WeaponHydrationBreakdownPlan::isGameHelperCall);
        if (weaponCalls.size() != WEAPON_CALL_SITES
                || projectileCalls.size() != PROJECTILE_CALL_SITES
                || projectileJsonOtherCalls.size() != PROJECTILE_JSON_OTHER_CALL_SITES
                || projectileSchemaCalls.size() != PROJECTILE_SCHEMA_CALL_SITES
                || projectileSpecMethodCalls.size() != PROJECTILE_SPEC_METHOD_CALL_SITES
                || projectileGameHelperCalls.size() != PROJECTILE_GAME_HELPER_CALL_SITES) {
            return false;
        }
        wrap(weapon, weaponCalls, WEAPON_SLOT);
        wrap(projectile, projectileCalls, PROJECTILE_SLOT);
        wrap(projectile, projectileJsonOtherCalls, PROJECTILE_JSON_OTHER_SLOT);
        wrap(projectile, projectileSchemaCalls, PROJECTILE_SCHEMA_SLOT);
        wrap(projectile, projectileSpecMethodCalls, PROJECTILE_SPEC_METHOD_SLOT);
        wrap(projectile, projectileGameHelperCalls, PROJECTILE_GAME_HELPER_SLOT);
        return true;
    }

    private static List<MethodInsnNode> numericCalls(MethodNode method) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && JSON_OBJECT.equals(call.owner)
                    && (("optDouble".equals(call.name)
                            && "(Ljava/lang/String;D)D".equals(call.desc))
                        || ("getDouble".equals(call.name)
                            && "(Ljava/lang/String;)D".equals(call.desc)))) {
                result.add(call);
            }
        }
        return result;
    }

    private static List<MethodInsnNode> matchingCalls(
            MethodNode method, java.util.function.Predicate<MethodInsnNode> predicate) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && predicate.test(call)) {
                result.add(call);
            }
        }
        return result;
    }

    private static boolean isOtherJsonCall(MethodInsnNode call) {
        return call.owner.startsWith("org/json/")
                && !"<init>".equals(call.name)
                && !(JSON_OBJECT.equals(call.owner)
                    && (("optDouble".equals(call.name)
                            && "(Ljava/lang/String;D)D".equals(call.desc))
                        || ("getDouble".equals(call.name)
                            && "(Ljava/lang/String;)D".equals(call.desc))));
    }

    private static boolean isSchemaCall(MethodInsnNode call) {
        return "com/fs/starfarer/loading/D".equals(call.owner);
    }

    private static boolean isSpecMethodCall(MethodInsnNode call) {
        return call.owner.startsWith("com/fs/starfarer/loading/specs/")
                && !"<init>".equals(call.name);
    }

    private static boolean isGameHelperCall(MethodInsnNode call) {
        return call.owner.startsWith("com/fs/")
                && !"<init>".equals(call.name)
                && !"com/fs/starfarer/loading/LoadingUtils".equals(call.owner)
                && !isSchemaCall(call)
                && !isSpecMethodCall(call);
    }

    private static void wrap(MethodNode method, List<MethodInsnNode> calls, int slot) {
        int tokenLocal = method.maxLocals;
        method.maxLocals += 2;
        for (MethodInsnNode call : calls) {
            InsnList before = new InsnList();
            before.add(new LdcInsnNode(slot));
            before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                    "sampledHotCallStart", "(I)J", false));
            before.add(new VarInsnNode(Opcodes.LSTORE, tokenLocal));
            method.instructions.insertBefore(call, before);

            InsnList after = new InsnList();
            after.add(new LdcInsnNode(slot));
            after.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
            after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                    "sampledHotCallEnd", "(IJ)V", false));
            method.instructions.insert(call, after);
        }
    }

    private static boolean hasRuntimeCalls(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)
                    && ("sampledHotCallStart".equals(call.name)
                        || "sampledHotCallEnd".equals(call.name))) {
                return true;
            }
        }
        return false;
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
}
