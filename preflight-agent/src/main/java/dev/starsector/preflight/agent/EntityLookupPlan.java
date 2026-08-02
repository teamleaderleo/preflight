package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Puts an index in front of {@code BaseLocation.getEntityById}.
 *
 * <p>The shipped method scans the location's entity list — twice over, once through a per-frame map
 * rebuild and once through a case-insensitive fallback that allocates two Strings per entity — and
 * {@code CampaignEngine} runs it over every location in the sector. One unresolved id costs 1.49 ms
 * on a 100 MB save. {@link EntityLookupRuntime} answers the same question from a map.
 *
 * <p>Nothing is patched. The original method is renamed and kept verbatim, and the wrapper installed
 * under the original name delegates to it whenever the runtime declines — which is every time the
 * gate is off, every time the index has no entry, and every time anything at all goes wrong. The
 * shipped behaviour is one branch away at all times.
 *
 * <p>The wrapper emits the two typed calls itself rather than leaving them to the runtime, because
 * it is woven <em>into</em> {@code BaseLocation} and can therefore name the game's own classes
 * directly. That keeps reflection off the hot path entirely, and it puts the membership check —
 * {@code getObjects().contains(entity)}, the game's own {@code forFastContains} HashSet — in the
 * one place that can see live repository state.
 *
 * <p>{@code BaseLocation} implements {@code com.fs.util.DoNotObfuscate}, so unlike every other seam
 * in this project its name and its members are stable rather than a happy accident.
 */
final class EntityLookupPlan {
    static final String TARGET_CLASS = "com/fs/starfarer/campaign/BaseLocation";
    static final String LOOKUP_METHOD = "getEntityById";
    static final String LOOKUP_DESCRIPTOR =
            "(Ljava/lang/String;)Lcom/fs/starfarer/api/campaign/SectorEntityToken;";
    static final String ENTITIES_METHOD = "getAllEntities";
    static final String ENTITIES_DESCRIPTOR = "()Ljava/util/List;";
    static final String OBJECTS_METHOD = "getObjects";
    static final String OBJECTS_DESCRIPTOR = "()Lcom/fs/util/container/repo/ObjectRepository;";

    private static final String ORIGINAL_LOOKUP = "preflight$original$getEntityById";
    private static final String RUNTIME = "dev/starsector/preflight/agent/EntityLookupRuntime";
    private static final String REPOSITORY = "com/fs/util/container/repo/ObjectRepository";
    private static final String TOKEN = "com/fs/starfarer/api/campaign/SectorEntityToken";

    private EntityLookupPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(LOOKUP_METHOD, LOOKUP_DESCRIPTOR)
                || !signature.hasMethod(ENTITIES_METHOD, ENTITIES_DESCRIPTOR)
                || !signature.hasMethod(OBJECTS_METHOD, OBJECTS_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!TARGET_CLASS.equals(owner.name) || hasMethod(owner, ORIGINAL_LOOKUP)) {
            return null;
        }

        MethodNode lookup = uniqueMethod(owner);
        if (lookup == null || !eligible(lookup)) {
            return null;
        }

        int access = lookup.access;
        lookup.name = ORIGINAL_LOOKUP;
        owner.methods.add(wrapper(owner.name, access));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        // Only now, so the counters cannot describe a rewrite that never happened and the gate
        // cannot open on a class this plan declined.
        EntityLookupRuntime.indexInstalled();
        return writer.toByteArray();
    }

    /**
     * <pre>
     *   Object found = EntityLookupRuntime.lookup(getAllEntities(), this, id);
     *   if (found != null &amp;&amp; getObjects().contains(found)) return (SectorEntityToken) found;
     *   return preflight$original$getEntityById(id);
     * </pre>
     *
     * <p>The runtime call comes first so that the gate-off path is one static call returning null,
     * a branch, and the original. The membership check is second because it is the expensive half of
     * being right: it is what makes an index that has gone stale unable to answer with an entity the
     * location no longer holds.
     */
    private static MethodNode wrapper(String owner, int access) {
        MethodNode wrapper =
                new MethodNode(Opcodes.ASM9, access, LOOKUP_METHOD, LOOKUP_DESCRIPTOR, null, null);
        LabelNode delegate = new LabelNode();

        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, ENTITIES_METHOD, ENTITIES_DESCRIPTOR, false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "lookup",
                "(Ljava/util/List;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));

        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFNULL, delegate));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, OBJECTS_METHOD, OBJECTS_DESCRIPTOR, false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, REPOSITORY, "contains", "(Ljava/lang/Object;)Z", false));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, delegate));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        wrapper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, TOKEN));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));

        wrapper.instructions.add(delegate);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, owner, ORIGINAL_LOOKUP, LOOKUP_DESCRIPTOR, false));
        wrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
        return wrapper;
    }

    /** Refuses anything the rename would change the meaning of. */
    private static boolean eligible(MethodNode lookup) {
        int forbidden = Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC
                | Opcodes.ACC_BRIDGE | Opcodes.ACC_STATIC;
        return (lookup.access & forbidden) == 0 && lookup.instructions.size() > 0;
    }

    /** Null unless exactly one method carries the name and descriptor, so an overload cannot slip in. */
    private static MethodNode uniqueMethod(ClassNode owner) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (LOOKUP_METHOD.equals(method.name) && LOOKUP_DESCRIPTOR.equals(method.desc)) {
                if (found != null) {
                    return null;
                }
                found = method;
            }
        }
        return found;
    }

    private static boolean hasMethod(ClassNode owner, String name) {
        return owner.methods.stream().anyMatch(method -> name.equals(method.name));
    }
}
